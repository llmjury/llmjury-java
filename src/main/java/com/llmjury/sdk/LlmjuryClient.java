package com.llmjury.sdk;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The LLMJury client: deterministic {@code assign} + non-blocking {@code track}. Construct once per
 * process via {@link #builder} and reuse; call {@link #close} at shutdown. Behavioral port of the
 * reference Python SDK.
 *
 * <p>Design:
 *
 * <ul>
 * <li>{@link #assign} is <b>pure local compute</b> against the polled experiment config — no
 * network on the hot path, so it never blocks. It returns the variant <i>key</i> (or {@code null}
 * while the config is still being fetched), reproducing the frozen bucketing hash exactly.
 * <li>{@link #track} only appends to an in-memory buffer that flushes asynchronously; it never
 * blocks or throws into the caller.
 * <li>Experiment config is fetched by <b>polling</b> {@code GET /v1/config} with
 * {@code If-None-Match}/304 into an in-process cache (default every 60s). When a track (ingest) ack
 * carries a newer {@code config_version} than what is cached, an immediate refresh is triggered. No
 * SSE/WebSocket.
 * <li>Authentication is the org <b>publishable</b> key via the {@code X-API-Key} header.
 * </ul>
 */
public final class LlmjuryClient implements AutoCloseable {

    /** Production API base URL. Override with {@code baseUrl} or the {@code LLMJURY_BASE_URL} env var. */
    static final String DEFAULT_BASE_URL = "https://api.llmjury.com";

    private final System.Logger log = System.getLogger("llmjury");
    private final Transport transport;
    private final long pollIntervalMillis;
    private final EventBuffer buffer;

    private final Object configLock = new Object();
    private final Map<String, CachedConfig> configs = new HashMap<>();
    private final Map<String, String> etags = new HashMap<>();
    private long highestVersion;

    private final Object pollMonitor = new Object();
    private final Set<String> pending = new HashSet<>();
    private boolean pollWake;
    private volatile boolean stopped;
    private final Thread pollThread;

    private LlmjuryClient(Builder builder) {
        this.transport = builder.transport != null ? builder.transport : defaultTransport(builder);
        this.pollIntervalMillis = builder.configPollInterval.toMillis();
        this.buffer = new EventBuffer(
                this::sendEvents, builder.flushInterval.toMillis(), builder.flushSize, builder.offlineStore);
        this.pollThread = new Thread(this::pollLoop, "llmjury-config");
        this.pollThread.setDaemon(true);
        this.pollThread.start();
        // Prefetch declared experiments so the first assign resolves without a manual warm-up call.
        for (String experimentId : builder.experiments) {
            requestRefresh(experimentId);
        }
    }

    private static HttpTransport defaultTransport(Builder builder) {
        String apiKey = builder.apiKey != null ? builder.apiKey : System.getenv("LLMJURY_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "llmjury: missing API key. Pass builder(apiKey) or set the LLMJURY_API_KEY environment variable.");
        }
        String baseUrl = builder.baseUrl;
        if (baseUrl == null) {
            String fromEnv = System.getenv("LLMJURY_BASE_URL");
            baseUrl = fromEnv != null ? fromEnv : DEFAULT_BASE_URL;
        }
        return new HttpTransport(baseUrl, apiKey, builder.requestTimeout);
    }

    /**
     * Start building a client.
     *
     * @param apiKey
     *            the org's <b>publishable</b> API key (safe in client-side code)
     * @return a builder with defaults matching the Python/TS SDKs
     */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    /**
     * Start building a client, reading the publishable key from the {@code LLMJURY_API_KEY}
     * environment variable so no secret appears in code.
     *
     * @return a builder with defaults matching the Python/TS SDKs
     */
    public static Builder builder() {
        return new Builder(null);
    }

    // -- public API -----------------------------------------------------------------------------

    /**
     * Resolve a user straight to the prompt text for their assigned variant.
     *
     * <p>{@code defaultPrompt} is the <b>in-code fallback prompt</b> — it is returned whenever
     * LLMJury cannot be reached (config not yet cached), assignment fails, or the assigned variant
     * has no prompt configured. This is the recommended integration: the app always has a working
     * prompt even during a full LLMJury outage. Never blocks, never throws.
     *
     * <p>Track the exposure with the returned variant when it is non-null; on a {@code null}
     * variant the user saw the default path and no exposure should be recorded.
     *
     * @param experiment
     *            the experiment id
     * @param user
     *            the stable user identifier
     * @param defaultPrompt
     *            the in-code fallback prompt (never returned as null)
     * @return the prompt assignment (never null)
     */
    public PromptAssignment getPrompt(String experiment, String user, String defaultPrompt) {
        try {
            String variant = assign(experiment, user);
            if (variant == null) {
                return new PromptAssignment(null, defaultPrompt, true);
            }
            CachedConfig cached;
            synchronized (configLock) {
                cached = configs.get(experiment);
            }
            String prompt = null;
            if (cached != null) {
                for (AllocationSlice slice : cached.allocation()) {
                    if (slice.variant().equals(variant)) {
                        prompt = slice.prompt();
                        break;
                    }
                }
            }
            if (prompt == null || prompt.isEmpty()) {
                return new PromptAssignment(variant, defaultPrompt, true);
            }
            return new PromptAssignment(variant, prompt, false);
        } catch (Exception exception) { // must never throw into the host app.
            log.log(
                    System.Logger.Level.WARNING,
                    "llmjury: getPrompt failed for " + experiment + "/" + user + ": " + exception);
            return new PromptAssignment(null, defaultPrompt, true);
        }
    }

    /**
     * Bind one experiment to one user for the request's duration — the setup-once surface. See
     * {@link ExperimentSession} for the full usage pattern.
     *
     * @param experiment
     *            the experiment id or unique name
     * @param user
     *            the stable user identifier
     * @return the bound session (never null; on the fallback path its variant is null and it
     *         records nothing)
     */
    public ExperimentSession session(String experiment, String user) {
        return new ExperimentSession(this, experiment, user);
    }

    /**
     * Resolve a user to their variant's custom variables, merged over in-code {@code defaults}.
     *
     * <p>Pure client-memory read (the polled config cache) — never a request-path API call. The
     * variant's prompt, when configured, is included under the {@code "prompt"} key. On any
     * failure path the defaults come back unchanged with {@code fallback() == true}. Never throws.
     *
     * @param experiment
     *            the experiment id or unique name
     * @param user
     *            the stable user identifier
     * @param defaults
     *            in-code default values (never returned as null)
     * @return the merged variables (never null)
     */
    public VariantVariables getVariables(String experiment, String user, Map<String, String> defaults) {
        Map<String, String> merged = new HashMap<>(defaults == null ? Map.of() : defaults);
        try {
            String variant = assign(experiment, user);
            if (variant == null) {
                return new VariantVariables(null, merged, true);
            }
            CachedConfig cached;
            synchronized (configLock) {
                cached = configs.get(experiment);
            }
            Map<String, String> configured = Map.of();
            String prompt = null;
            if (cached != null) {
                for (AllocationSlice slice : cached.allocation()) {
                    if (slice.variant().equals(variant)) {
                        configured = slice.variables();
                        prompt = slice.prompt();
                        break;
                    }
                }
            }
            if (prompt != null && !prompt.isEmpty()) {
                merged.put("prompt", prompt);
            }
            merged.putAll(configured);
            return new VariantVariables(variant, merged, configured.isEmpty() && (prompt == null || prompt.isEmpty()));
        } catch (Exception exception) { // must never throw into the host app.
            log.log(
                    System.Logger.Level.WARNING,
                    "llmjury: getVariables failed for " + experiment + "/" + user + ": " + exception);
            return new VariantVariables(null, merged, true);
        }
    }

    /** The wrapped body of an intercepted model call; may throw — the exception stays checked. */
    @FunctionalInterface
    public interface ModelCallBody<T, E extends Exception> {
        /**
         * @param call
         *            the interception handle (attach prompt/response/tokens; mark first token)
         * @return the body's result, returned unchanged by the wrapper
         * @throws E
         *             anything the body throws — re-thrown after the error is recorded
         */
        T run(ModelCall call) throws E;
    }

    /**
     * Wrap an LLM call so its implicit metrics are captured with NO explicit tracking.
     *
     * <pre>{@code
     * PromptAssignment a = client.getPrompt("checkout-copy", userId, DEFAULT_PROMPT);
     * String reply = client.interceptModelCall("checkout-copy", userId, a.variant(), call -> {
     *     Response response = llm(a.prompt(), userInput);
     *     call.prompt(a.prompt()).response(response.text()).tokensOutput(response.outputTokens());
     *     return response.text();
     * });
     * }</pre>
     *
     * <p>On completion the SDK tracks the exposure and a {@code model_call} event with the measured
     * {@code latency_ms}, {@code ttft_ms} (when {@link ModelCall#markFirstToken()} was called), and
     * the attached fields. A thrown exception is recorded as {@code metadata.error = true} and
     * re-thrown. When {@code variant} is {@code null} (the getPrompt fallback path) nothing is
     * recorded. Only business outcomes still need {@link #track}.
     *
     * @param experiment
     *            the experiment id or unique name
     * @param user
     *            the stable user identifier
     * @param variant
     *            the assigned variant (from {@link #getPrompt}/{@link #assign}), or {@code null}
     * @param body
     *            the model call to wrap
     * @return the body's result
     * @throws E
     *             whatever the body throws, after the failure is recorded
     */
    public <T, E extends Exception> T interceptModelCall(
            String experiment, String user, String variant, ModelCallBody<T, E> body) throws E {
        ModelCall call = new ModelCall(this::track, experiment, user, variant);
        try {
            T result = body.run(call);
            call.finish(false);
            return result;
        } catch (Exception exception) {
            call.finish(true);
            throw uncheckedRethrow(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Exception> E uncheckedRethrow(Exception exception) throws E {
        throw (E) exception;
    }

    /**
     * Resolve a user to a variant key for an experiment (deterministic, frozen hash).
     *
     * <p>Pure local compute against the cached config — never blocks, never throws. Returns
     * {@code null} if the config is not yet cached; the SDK fetches it in the background, so a
     * later call resolves. Callers should treat {@code null} as "fall back to your control
     * behaviour".
     *
     * @param experiment
     *            the experiment id
     * @param user
     *            the stable user identifier
     * @return the variant key, or {@code null} while the config is unknown
     */
    public String assign(String experiment, String user) {
        try {
            CachedConfig cached;
            synchronized (configLock) {
                cached = configs.get(experiment);
            }
            if (cached == null) {
                requestRefresh(experiment);
                return null;
            }
            // ALWAYS hash the canonical id — hashing a name would diverge from server assignment.
            return Bucketing.assignVariant(
                    cached.salt(), user, cached.experimentId(), cached.bucketCount(), cached.allocation());
        } catch (Exception exception) { // belt and braces — assign must never throw into the host app.
            log.log(
                    System.Logger.Level.WARNING,
                    "llmjury: assign failed for " + experiment + "/" + user + ": " + exception);
            return null;
        }
    }

    /**
     * Buffer an event ({@code exposure} | {@code model_call} | {@code business_event}).
     *
     * <p>The payload carries the event fields ({@code experiment_id}, {@code user_id} and any
     * metrics). The SDK stamps a client-generated {@code event_id} and {@code timestamp} if absent.
     * Non-blocking; never throws into the caller.
     *
     * @param event
     *            the event type
     * @param payload
     *            the event fields; copied, so the caller's map is not retained
     */
    public void track(String event, Map<String, Object> payload) {
        try {
            Map<String, Object> record = buildEvent(event, payload);
            if (record == null) {
                return;
            }
            buffer.add(record);
        } catch (Exception exception) { // never propagate.
            log.log(System.Logger.Level.WARNING, "llmjury: track failed for " + event + ": " + exception);
        }
    }

    /**
     * Blocking, exception-safe config fetch. Useful at startup so the first {@link #assign}
     * resolves; not part of the hot path.
     *
     * @param experimentId
     *            the experiment to fetch
     * @return true if the config is cached afterwards
     */
    public boolean refreshConfig(String experimentId) {
        fetchConfig(experimentId);
        synchronized (configLock) {
            return configs.containsKey(experimentId);
        }
    }

    /**
     * Best-effort blocking flush of buffered events (e.g. before a short-lived process exits).
     *
     * @param timeout
     *            how long to wait for the buffer to drain
     */
    public void flush(Duration timeout) {
        buffer.flush(timeout.toMillis());
    }

    /** Flush and stop background threads with a 5s bound. Called by try-with-resources. */
    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    /**
     * Flush and stop background threads. Call at process shutdown.
     *
     * @param timeout
     *            per-thread join bound
     */
    public void close(Duration timeout) {
        stopped = true;
        synchronized (pollMonitor) {
            pollMonitor.notifyAll();
        }
        try {
            pollThread.join(timeout.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        buffer.close(timeout.toMillis());
    }

    // -- internals ------------------------------------------------------------------------------

    /** Package-private test seam (mirrors the Python tests peeking at {@code _buffer}). */
    EventBuffer buffer() {
        return buffer;
    }

    private Map<String, Object> buildEvent(String event, Map<String, Object> payload) {
        Map<String, Object> record = new HashMap<>(payload);
        record.put("type", event);
        record.putIfAbsent("event_id", UUID.randomUUID().toString());
        record.putIfAbsent("timestamp", Instant.now().toString());
        if (isBlank(record.get("experiment_id")) || isBlank(record.get("user_id"))) {
            log.log(System.Logger.Level.WARNING, "llmjury: dropping " + event + " event missing experiment_id/user_id");
            return null;
        }
        // Name-addressed events normalize to the canonical id so the analysis pipeline (keyed by
        // id) attributes them; unknown identifiers pass through untouched. The held config version
        // is stamped so the version-scoped rollups attribute the event to the config it ran under.
        CachedConfig cached;
        synchronized (configLock) {
            cached = configs.get(String.valueOf(record.get("experiment_id")));
        }
        if (cached != null) {
            record.put("experiment_id", cached.experimentId());
            record.putIfAbsent("config_version", cached.version());
        }
        return record;
    }

    private static boolean isBlank(Object value) {
        return !(value instanceof String) || ((String) value).isEmpty();
    }

    private void sendEvents(List<Map<String, Object>> batch) throws Exception {
        IngestAck ack = transport.postEvents(batch);
        long known;
        synchronized (configLock) {
            known = highestVersion;
        }
        if (ack.configVersion() > known) {
            // Staleness hint: org config advanced past what we hold — refresh everything we know.
            Set<String> knownExperiments;
            synchronized (configLock) {
                knownExperiments = new HashSet<>(configs.keySet());
            }
            for (String experimentId : knownExperiments) {
                requestRefresh(experimentId);
            }
        }
    }

    private void requestRefresh(String experimentId) {
        synchronized (pollMonitor) {
            pending.add(experimentId);
            pollWake = true;
            pollMonitor.notifyAll();
        }
    }

    private void pollLoop() {
        while (!stopped) {
            boolean woke;
            Set<String> targets = new HashSet<>();
            synchronized (pollMonitor) {
                if (!pollWake && !stopped) {
                    try {
                        pollMonitor.wait(pollIntervalMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                woke = pollWake;
                pollWake = false;
                targets.addAll(pending);
                pending.clear();
            }
            if (!woke) { // periodic tick — re-poll everything we already track.
                synchronized (configLock) {
                    targets.addAll(configs.keySet());
                }
            }
            for (String experimentId : targets) {
                if (stopped) {
                    return;
                }
                fetchConfig(experimentId);
            }
        }
    }

    private void fetchConfig(String experimentId) {
        try {
            String etag;
            synchronized (configLock) {
                etag = etags.get(experimentId);
            }
            ConfigResponse response = transport.getConfig(experimentId, etag);
            if (response.status() == 304) {
                return;
            }
            storeConfigs(response, experimentId);
        } catch (Exception exception) { // keep the last-known config on any fetch error.
            log.log(System.Logger.Level.WARNING, "llmjury: config fetch failed for " + experimentId + ": " + exception);
        }
    }

    private void storeConfigs(ConfigResponse response, String requestedId) {
        synchronized (configLock) {
            for (CachedConfig config : response.configs()) {
                configs.put(config.experimentId(), config);
                if (config.name() != null && !config.name().isEmpty()) {
                    configs.put(config.name(), config); // names are unique per org
                }
                highestVersion = Math.max(highestVersion, config.version());
            }
            if (response.etag() != null) {
                etags.put(requestedId, response.etag());
            }
        }
    }

    /** Fluent builder; only the publishable API key is required. */
    public static final class Builder {

        private final String apiKey;
        private String baseUrl;
        private final List<String> experiments = new java.util.ArrayList<>();
        private Transport transport;
        private Duration flushInterval = Duration.ofSeconds(1);
        private int flushSize = 1000;
        private Duration configPollInterval = Duration.ofSeconds(60);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private OfflineStore offlineStore;

        private Builder(String apiKey) {
            this.apiKey = apiKey; // may be null; resolved from LLMJURY_API_KEY at build() time.
        }

        /** The LLMJury API base URL (default {@code https://api.llmjury.com} or {@code LLMJURY_BASE_URL}). */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** Prefetch these experiments at startup so the first {@link #assign} resolves immediately. */
        public Builder experiments(String... experimentIds) {
            for (String id : experimentIds) {
                this.experiments.add(Objects.requireNonNull(id, "experimentId"));
            }
            return this;
        }

        /** Replace the HTTP transport (tests inject an in-process fake here). */
        public Builder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        /** How often the buffer flushes when below the size trigger (default 1s). */
        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        /** Buffer size that triggers an immediate flush (default 1,000). */
        public Builder flushSize(int flushSize) {
            this.flushSize = flushSize;
            return this;
        }

        /** How often {@code GET /v1/config} is polled (default 60s). */
        public Builder configPollInterval(Duration configPollInterval) {
            this.configPollInterval = configPollInterval;
            return this;
        }

        /** Per-request transport timeout for the default {@link HttpTransport} (default 5s). */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** Spill undeliverable events to this file for 24h-bounded replay on the next start. */
        public Builder offlinePath(String path) {
            this.offlineStore = new FileOfflineStore(path);
            return this;
        }

        /** Use a custom {@link OfflineStore} instead of the file-backed default. */
        public Builder offlineStore(OfflineStore offlineStore) {
            this.offlineStore = offlineStore;
            return this;
        }

        public LlmjuryClient build() {
            return new LlmjuryClient(this);
        }
    }
}

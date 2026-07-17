package com.llmjury.sdk;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Handle for one intercepted model call (see {@link LlmjuryClient#interceptModelCall}). The wrapper
 * measures {@code latency_ms} automatically, {@code ttft_ms} when {@link #markFirstToken()} is
 * called, and records {@code metadata.error = true} when the wrapped body throws — the exception is
 * always re-thrown, never swallowed. Attach the remaining fields with the fluent setters (the Java
 * provider SDKs are strongly typed, so there is no duck-typed extraction here).
 */
public final class ModelCall {

    private final BiConsumer<String, Map<String, Object>> track;
    private final String experiment;
    private final String user;
    private final String variant;
    private final long startedNanos;
    private final Map<String, Object> fields = new HashMap<>();
    private final Map<String, Object> metadata = new HashMap<>();
    private Long ttftMs;

    ModelCall(BiConsumer<String, Map<String, Object>> track, String experiment, String user, String variant) {
        this.track = track;
        this.experiment = experiment;
        this.user = user;
        this.variant = variant;
        this.startedNanos = System.nanoTime();
    }

    /** Record time-to-first-token (first call wins) — call when the first stream chunk lands. */
    public ModelCall markFirstToken() {
        if (ttftMs == null) {
            ttftMs = elapsedMs();
        }
        return this;
    }

    /** @param prompt the prompt sent to the model (judged server-side) */
    public ModelCall prompt(String prompt) {
        return put("prompt", prompt);
    }

    /** @param response the model's response text (judged server-side) */
    public ModelCall response(String response) {
        return put("response", response);
    }

    /** @param model the model identifier */
    public ModelCall model(String model) {
        return put("model", model);
    }

    /** @param tokensInput prompt/input token count */
    public ModelCall tokensInput(int tokensInput) {
        return put("tokens_input", tokensInput);
    }

    /** @param tokensOutput completion/output token count */
    public ModelCall tokensOutput(int tokensOutput) {
        return put("tokens_output", tokensOutput);
    }

    /** @param costUsd the call's cost in USD */
    public ModelCall costUsd(double costUsd) {
        return put("cost_usd", costUsd);
    }

    /** @param key metadata key @param value metadata value (carried on the event's metadata map) */
    public ModelCall metadata(String key, Object value) {
        metadata.put(key, value);
        return this;
    }

    /** Emit the exposure + model_call events; package-private (the wrapper calls this). */
    void finish(boolean failed) {
        if (variant == null) {
            return; // fallback path (config unavailable): record nothing, change nothing
        }
        Map<String, Object> base = new HashMap<>();
        base.put("experiment_id", experiment);
        base.put("user_id", user);
        base.put("variant", variant);
        track.accept("exposure", new HashMap<>(base));

        Map<String, Object> event = new HashMap<>(base);
        event.putAll(fields);
        event.put("latency_ms", elapsedMs());
        if (ttftMs != null) {
            event.put("ttft_ms", ttftMs);
        }
        if (failed) {
            metadata.put("error", true);
            event.remove("response"); // a failed call has no trustworthy response payload
        }
        if (!metadata.isEmpty()) {
            event.put("metadata", new HashMap<>(metadata));
        }
        track.accept("model_call", event);
    }

    private ModelCall put(String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
        return this;
    }

    private long elapsedMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}

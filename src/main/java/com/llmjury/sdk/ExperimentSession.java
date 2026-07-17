package com.llmjury.sdk;

import java.util.HashMap;
import java.util.Map;

/**
 * One experiment bound to one user — the setup-once surface (see {@link LlmjuryClient#session}).
 * Java cannot transparently proxy arbitrary provider clients (they are concrete classes), so the
 * session is the OkHttp-style equivalent: bind once per request, then use it without repeating the
 * experiment/user/variant plumbing.
 *
 * <pre>{@code
 * ExperimentSession s = client.session("checkout-copy", userId);   // once per request
 * Map<String, String> v = s.variables(Map.of("model", "claude-haiku-4-5"));
 *
 * String reply = s.intercept(call -> {                              // latency/errors automatic
 *     Response r = llm.create(v.get("model"), v.get("prompt"), userInput);
 *     call.response(r.text()).tokensOutput(r.outputTokens());
 *     return r.text();
 * });
 *
 * s.trackOutcome("conversion", 1);                                  // the only explicit metric
 * }</pre>
 */
public final class ExperimentSession {

    private final LlmjuryClient client;
    private final String experiment;
    private final String user;
    private final String variant;

    ExperimentSession(LlmjuryClient client, String experiment, String user) {
        this.client = client;
        this.experiment = experiment;
        this.user = user;
        this.variant = client.assign(experiment, user);
    }

    /** @return the assigned variant, or {@code null} while the config is unknown (fallback path) */
    public String variant() {
        return variant;
    }

    /** @return this user's variant variables merged over {@code defaults} (client-memory read) */
    public Map<String, String> variables(Map<String, String> defaults) {
        return client.getVariables(experiment, user, defaults).values();
    }

    /** @return the variant's prompt, or {@code defaultPrompt} on any fallback path */
    public String prompt(String defaultPrompt) {
        return client.getPrompt(experiment, user, defaultPrompt).prompt();
    }

    /**
     * Run the model call with implicit metrics captured (latency, TTFT, errors — see
     * {@link LlmjuryClient#interceptModelCall}).
     *
     * @param body
     *            the model call
     * @return the body's result
     * @throws E
     *             whatever the body throws, after the failure is recorded
     */
    public <T, E extends Exception> T intercept(LlmjuryClient.ModelCallBody<T, E> body) throws E {
        return client.interceptModelCall(experiment, user, variant, body);
    }

    /**
     * Track the business outcome — the only explicit metric call left.
     *
     * @param businessMetric
     *            the metric name (e.g. {@code conversion})
     * @param value
     *            the outcome value
     */
    public void trackOutcome(String businessMetric, double value) {
        if (variant == null) {
            return; // fallback path: nothing to attribute
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("experiment_id", experiment);
        payload.put("user_id", user);
        payload.put("variant", variant);
        payload.put("business_metric", businessMetric);
        payload.put("value", value);
        client.track("business_event", payload);
    }
}

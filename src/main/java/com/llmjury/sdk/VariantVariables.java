package com.llmjury.sdk;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Result of {@link LlmjuryClient#getVariables} — always usable, never thrown from.
 *
 * <p>{@code values} starts from the caller's in-code defaults and overlays the assigned variant's
 * configured variables (plus its prompt, when set, under the {@code "prompt"} key). Resolved
 * entirely from the client-memory config cache — no API call on this path; the cache refreshes in
 * the background (60s poll) and immediately when an event send reports a newer config version.
 * {@code fallback} is {@code true} when the defaults came back unmodified (config unreachable).
 */
public final class VariantVariables {

    private final String variant;
    private final Map<String, String> values;
    private final boolean fallback;

    public VariantVariables(String variant, Map<String, String> values, boolean fallback) {
        this.variant = variant;
        this.values = Collections.unmodifiableMap(Objects.requireNonNull(values, "values"));
        this.fallback = fallback;
    }

    /** @return the assigned variant key, or {@code null} when assignment could not resolve */
    public String variant() {
        return variant;
    }

    /** @return the merged variables (defaults ← variant's configured values), never null */
    public Map<String, String> values() {
        return values;
    }

    /** @return one merged value by key, or {@code null} when absent */
    public String get(String key) {
        return values.get(key);
    }

    /** @return whether the defaults came back unmodified (config unreachable / no variables) */
    public boolean fallback() {
        return fallback;
    }
}

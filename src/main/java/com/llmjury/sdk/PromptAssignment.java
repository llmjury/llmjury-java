package com.llmjury.sdk;

import java.util.Objects;

/**
 * Result of {@link LlmjuryClient#getPrompt} — always usable, never thrown from.
 *
 * <p>{@code variant} is the assigned variant key, or {@code null} when assignment could not resolve
 * (config not yet cached / backend unreachable). {@code prompt} is the text to use: the variant's
 * configured prompt when available, otherwise the caller's in-code default. {@code fallback} is
 * {@code true} whenever the returned prompt is the default rather than the variant's configured
 * prompt.
 */
public final class PromptAssignment {

    private final String variant;
    private final String prompt;
    private final boolean fallback;

    public PromptAssignment(String variant, String prompt, boolean fallback) {
        this.variant = variant;
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.fallback = fallback;
    }

    /** @return the assigned variant key, or {@code null} when assignment could not resolve */
    public String variant() {
        return variant;
    }

    /** @return the prompt text to use (never null) */
    public String prompt() {
        return prompt;
    }

    /** @return whether {@link #prompt()} is the caller's default rather than the configured prompt */
    public boolean fallback() {
        return fallback;
    }

    @Override
    public String toString() {
        return "PromptAssignment{variant=" + variant + ", fallback=" + fallback + "}";
    }
}

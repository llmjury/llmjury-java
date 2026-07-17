package com.llmjury.sdk;

import java.util.Objects;

/**
 * One ordered allocation slice: a variant key and its integer weight (spec/bucketing.md §3).
 *
 * <p>A plain final class rather than a record so the compiled bytecode stays on the Java 11 floor
 *.
 */
public final class AllocationSlice {

    private final String variant;
    private final int weight;
    private final String prompt;
    private final java.util.Map<String, String> variables;

    public AllocationSlice(String variant, int weight) {
        this(variant, weight, null, java.util.Collections.emptyMap());
    }

    /** @param prompt optional prompt text for the variant (from {@code GET /v1/config}), or null */
    public AllocationSlice(String variant, int weight, String prompt) {
        this(variant, weight, prompt, java.util.Collections.emptyMap());
    }

    /** @param variables optional per-variant custom variables (model, temperature, …), never null */
    public AllocationSlice(String variant, int weight, String prompt, java.util.Map<String, String> variables) {
        this.variant = Objects.requireNonNull(variant, "variant");
        this.weight = weight;
        this.prompt = prompt;
        this.variables = variables == null || variables.isEmpty()
                ? java.util.Collections.emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(variables));
    }

    public String variant() {
        return variant;
    }

    public int weight() {
        return weight;
    }

    /** @return the variant's configured prompt text, or null when none was configured */
    public String prompt() {
        return prompt;
    }

    /** @return the variant's custom variables (possibly empty, never null) */
    public java.util.Map<String, String> variables() {
        return variables;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AllocationSlice)) {
            return false;
        }
        AllocationSlice that = (AllocationSlice) other;
        return weight == that.weight && variant.equals(that.variant) && Objects.equals(prompt, that.prompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variant, weight, prompt);
    }

    @Override
    public String toString() {
        return "AllocationSlice{" + variant + "=" + weight + "}";
    }
}

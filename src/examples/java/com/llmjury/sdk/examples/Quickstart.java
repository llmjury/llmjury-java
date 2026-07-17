package com.llmjury.sdk.examples;

import com.llmjury.sdk.LlmjuryClient;
import java.util.HashMap;
import java.util.Map;

/**
 * The README sample: assign a user to a variant and track an exposure. Run it with
 * {@code ./gradlew runQuickstart} (set {@code LLMJURY_API_KEY}); without a server it still
 * exits cleanly — assign returns null (fall back to control) and track drops after bounded retries,
 * because the SDK never throws into the host app.
 */
public final class Quickstart {

    private Quickstart() {}

    public static void main(String[] args) {
        // builder() reads LLMJURY_API_KEY from the environment and defaults to https://api.llmjury.com.
        // Set LLMJURY_BASE_URL to point at a local stack. Falls back to a demo key so the sample runs
        // offline (assign returns null → control, track drops after bounded retries; the SDK never throws).
        LlmjuryClient.Builder builder = System.getenv("LLMJURY_API_KEY") != null
                ? LlmjuryClient.builder()
                : LlmjuryClient.builder("pk_local_dev");

        try (LlmjuryClient client = builder.experiments("exp_onboarding_copy").build()) {
            // experiments(...) prefetches the config so the first assign resolves without a manual
            // warm-up call. assign is still null until the background poll lands → treat null as control.

            String variant = client.assign("exp_onboarding_copy", "user-42");
            System.out.println("variant for user-42: " + (variant == null ? "(config not loaded — control)" : variant));

            Map<String, Object> exposure = new HashMap<>();
            exposure.put("experiment_id", "exp_onboarding_copy");
            exposure.put("user_id", "user-42");
            exposure.put("variant", variant == null ? "control" : variant);
            client.track("exposure", exposure);
        } // close() flushes buffered events and stops the background threads.
    }
}

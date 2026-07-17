package com.llmjury.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The mandatory cross-SDK determinism test (spec/bucketing.md §5). Reads the
 * shared fixture — bundled onto the test classpath from spec/fixtures/ — and asserts this SDK
 * reproduces {@code expected_variant} for every case. The Python and TypeScript
 * SDKs assert against the <i>same</i> fixture, so a green run here closes the
 * three-language guarantee.
 */
final class BucketingTest {

    private static final List<JsonObject> CASES = loadCases();

    private static List<JsonObject> loadCases() {
        InputStream stream = BucketingTest.class.getResourceAsStream("/spec/fixtures/bucketing-cases.json");
        assertNotNull(stream, "missing cross-language fixture on the test classpath");
        JsonObject fixture = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<JsonObject> cases = new ArrayList<>();
        for (JsonElement element : fixture.getAsJsonArray("cases")) {
            cases.add(element.getAsJsonObject());
        }
        return cases;
    }

    static Stream<JsonObject> cases() {
        return CASES.stream();
    }

    @Test
    void fixtureIsPresentAndSpansBucketCounts() {
        assertFalse(CASES.isEmpty(), "fixture has no cases");
        // The fixture deliberately covers more than one bucket_count (1,000 and 10,000) so the
        // per-experiment modulus is proven to come from config, never a hardcoded literal.
        Set<Integer> bucketCounts = new HashSet<>();
        for (JsonObject c : CASES) {
            bucketCounts.add(c.get("bucket_count").getAsInt());
        }
        assertTrue(bucketCounts.size() > 1, "fixture should span multiple bucket_count values");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void bucketingMatchesFixture(JsonObject testCase) {
        String salt = testCase.get("salt").getAsString();
        String userId = testCase.get("user_id").getAsString();
        String experimentId = testCase.get("experiment_id").getAsString();
        int bucketCount = testCase.get("bucket_count").getAsInt();

        if (testCase.has("bucket")) {
            assertEquals(
                    testCase.get("bucket").getAsInt(),
                    Bucketing.bucketOf(salt, userId, experimentId, bucketCount),
                    "bucket mismatch for " + experimentId + ":" + userId);
        }

        List<AllocationSlice> allocation = new ArrayList<>();
        for (JsonElement element : testCase.getAsJsonArray("allocation")) {
            JsonObject slice = element.getAsJsonObject();
            allocation.add(new AllocationSlice(
                    slice.get("variant").getAsString(), slice.get("weight").getAsInt()));
        }
        assertEquals(
                testCase.get("expected_variant").getAsString(),
                Bucketing.assignVariant(salt, userId, experimentId, bucketCount, allocation),
                "variant mismatch for " + experimentId + ":" + userId);
    }

    @Test
    void unicodeKeysHashViaUtf8Bytes() {
        // The key format is UTF-8 bytes of "{salt}:{user_id}:{experiment_id}" — multi-byte
        // characters must not diverge from the reference (which the fixture also covers; this is a
        // fast local guard with a known-stable property: determinism).
        int first = Bucketing.bucketOf("salzé", "ユーザー-1", "exp-π", 1000);
        int second = Bucketing.bucketOf("salzé", "ユーザー-1", "exp-π", 1000);
        assertEquals(first, second);
        assertTrue(first >= 0 && first < 1000);
    }
}

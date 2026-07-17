package com.llmjury.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Client behavior contract, mirroring the Python SDK's tests: {@code assign}/{@code track} never
 * block or throw into the host app, config resolves from the poll, offline events replay, and a
 * newer ack {@code config_version} triggers an immediate refresh.
 */
final class LlmjuryClientTest {

    private static final Duration LONG_INTERVAL = Duration.ofSeconds(60);

    @TempDir
    Path tempDir;

    private static List<CachedConfig> fiftyFiftyConfig() {
        return List.of(new CachedConfig(
                "exp_x",
                "s",
                1000,
                List.of(new AllocationSlice("control", 50), new AllocationSlice("treatment", 50)),
                3));
    }

    private static Map<String, Object> exposure(String user) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("experiment_id", "exp");
        payload.put("user_id", user);
        payload.put("variant", "control");
        return payload;
    }

    private static LlmjuryClient.Builder quietClient(Transport transport) {
        return LlmjuryClient.builder("pk_test")
                .transport(transport)
                .flushInterval(LONG_INTERVAL)
                .configPollInterval(LONG_INTERVAL);
    }

    @Test
    void trackNeverThrowsAndDropsOnTransportFailure() {
        LlmjuryClient client = quietClient(new FailingTransport()).build();
        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            client.track("exposure", exposure("u" + i));
        }
        // 50 enqueue calls return effectively instantly — no network on the hot path.
        assertTrue(System.nanoTime() - started < 500_000_000L);
        client.flush(Duration.ofSeconds(2));
        client.close(Duration.ofSeconds(5));
        // No offline store configured, so after exhausted retries the batch is dropped — not thrown.
        assertEquals(50, client.buffer().dropped());
    }

    @Test
    void assignReturnsNullAndNeverThrowsWithoutConfig() {
        LlmjuryClient client = quietClient(new FailingTransport()).build();
        assertNull(client.assign("exp", "user"));
        client.close();
    }

    @Test
    void assignResolvesDeterministicallyFromPolledConfig() {
        LlmjuryClient client =
                quietClient(new RecordingTransport(fiftyFiftyConfig())).build();
        assertTrue(client.refreshConfig("exp_x"));
        String variant = client.assign("exp_x", "user-0");
        assertNotNull(variant);
        assertTrue(variant.equals("control") || variant.equals("treatment"));
        // Deterministic: same inputs, same variant — and identical to the frozen hash directly.
        assertEquals(variant, client.assign("exp_x", "user-0"));
        assertEquals(
                Bucketing.assignVariant(
                        "s",
                        "user-0",
                        "exp_x",
                        1000,
                        List.of(new AllocationSlice("control", 50), new AllocationSlice("treatment", 50))),
                variant);
        client.close();
    }

    @Test
    void trackDropsEventsMissingRequiredIdsWithoutThrowing() {
        RecordingTransport transport = new RecordingTransport();
        LlmjuryClient client = quietClient(transport).build();
        client.track("exposure", Map.of("user_id", "u")); // no experiment_id — dropped at build time
        client.flush(Duration.ofSeconds(1));
        client.close(Duration.ofSeconds(5));
        assertTrue(transport.sentEvents.isEmpty());
    }

    @Test
    void trackedEventsAreStampedAndDelivered() {
        RecordingTransport transport = new RecordingTransport();
        LlmjuryClient client = quietClient(transport).build();
        client.track("exposure", exposure("u1"));
        client.flush(Duration.ofSeconds(2));
        client.close(Duration.ofSeconds(5));
        assertEquals(1, transport.sentEvents.size());
        Map<String, Object> sent = transport.sentEvents.get(0);
        assertEquals("exposure", sent.get("type"));
        assertNotNull(sent.get("event_id"), "client stamps a UUID event_id for dedup");
        assertNotNull(sent.get("timestamp"), "client stamps an ISO timestamp");
    }

    @Test
    void eventsSpilledOfflineReplayOnTheNextClient() {
        String path = tempDir.resolve("spill.jsonl").toString();

        // Client 1 cannot reach the network, so the event spills to the offline file.
        LlmjuryClient first =
                quietClient(new FailingTransport()).offlinePath(path).build();
        first.track("exposure", exposure("u"));
        first.flush(Duration.ofSeconds(2));
        first.close(Duration.ofSeconds(5));
        assertEquals(0, first.buffer().dropped());
        assertTrue(Files.exists(Path.of(path)));

        // Client 2 has a working transport: on startup it replays the spilled event and clears the file.
        RecordingTransport working = new RecordingTransport();
        LlmjuryClient second = quietClient(working).offlinePath(path).build();
        second.flush(Duration.ofSeconds(2));
        second.close(Duration.ofSeconds(5));

        assertEquals(1, working.sentEvents.size());
        assertEquals("exposure", working.sentEvents.get(0).get("type"));
        assertEquals("u", working.sentEvents.get(0).get("user_id"));
        assertTrue(Files.notExists(Path.of(path)), "cleared after a successful replay");
    }

    @Test
    void newerAckConfigVersionTriggersAnImmediateRefresh() {
        RecordingTransport transport = new RecordingTransport(fiftyFiftyConfig());
        LlmjuryClient client = quietClient(transport).build();
        assertTrue(client.refreshConfig("exp_x"));
        int callsAfterPrime = transport.configCalls.get();

        // The next ingest ack claims the org config moved past version 3 — the client must re-poll
        // without waiting for the 60s interval.
        transport.ackConfigVersion.set(99);
        client.track("exposure", exposure("u"));
        client.flush(Duration.ofSeconds(2));

        long deadline = System.nanoTime() + 2_000_000_000L;
        while (transport.configCalls.get() <= callsAfterPrime && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        client.close(Duration.ofSeconds(5));
        assertTrue(transport.configCalls.get() > callsAfterPrime, "ack staleness hint must trigger a refresh");
    }
}

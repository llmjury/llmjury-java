package com.llmjury.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline spill + replay: events survive an outage and replay with their original timestamps,
 * bounded to a 24h age.
 */
final class FileOfflineStoreTest {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    @TempDir
    Path tempDir;

    private static Map<String, Object> event(String id, String timestamp) {
        Map<String, Object> event = new HashMap<>();
        event.put("event_id", id);
        event.put("type", "exposure");
        event.put("timestamp", timestamp);
        return event;
    }

    @Test
    void loadReplayableRespectsThe24hBoundAndKeepsOriginalTimestamps() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        FileOfflineStore store =
                new FileOfflineStore(tempDir.resolve("spill.jsonl").toString(), DAY_MILLIS, clock::get);

        store.appendAll(List.of(event("old", "T-old")));
        clock.addAndGet(25 * 3600 * 1000L); // 25h later — the old record is now past the bound
        store.appendAll(List.of(event("new", "T-new")));
        clock.addAndGet(1);

        List<Map<String, Object>> replay = store.loadReplayable();
        assertEquals(1, replay.size());
        assertEquals("new", replay.get(0).get("event_id"));
        // Original timestamp is preserved verbatim — replay is faithful.
        assertEquals("T-new", replay.get(0).get("timestamp"));
    }

    @Test
    void toleratesMissingFileAndTornLines() throws IOException {
        Path path = tempDir.resolve("spill.jsonl");
        FileOfflineStore store = new FileOfflineStore(path.toString());
        assertTrue(store.loadReplayable().isEmpty(), "missing file replays nothing");

        store.appendAll(List.of(event("kept", "T")));
        // Simulate a crash mid-write: a torn, non-JSON final line must not break replay.
        Files.write(
                path, "{\"event\": {\"event_id\": \"torn".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        List<Map<String, Object>> replay = store.loadReplayable();
        assertEquals(1, replay.size());
        assertEquals("kept", replay.get(0).get("event_id"));
    }

    @Test
    void clearRemovesTheSpillFile() {
        Path path = tempDir.resolve("spill.jsonl");
        FileOfflineStore store = new FileOfflineStore(path.toString());
        store.appendAll(List.of(event("e", "T")));
        assertTrue(Files.exists(path));
        store.clear();
        assertFalse(Files.exists(path));
        store.clear(); // idempotent — clearing an absent file is not an error
    }

    @Test
    void integerEventFieldsSurviveTheRoundTrip() {
        FileOfflineStore store =
                new FileOfflineStore(tempDir.resolve("spill.jsonl").toString());
        Map<String, Object> event = event("typed", "T");
        event.put("latency_ms", 42L);
        store.appendAll(List.of(event));
        Object replayed = store.loadReplayable().get(0).get("latency_ms");
        // LONG_OR_DOUBLE parsing keeps integers integral (42, not 42.0) so re-ingest is faithful.
        assertEquals(42L, replayed);
    }
}

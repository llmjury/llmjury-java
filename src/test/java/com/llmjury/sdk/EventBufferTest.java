package com.llmjury.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The buffer is the non-blocking seam between the host app and the network: {@code add} never
 * blocks or throws, a failed flush retries a bounded number of times, then spills offline or
 * drops.
 */
final class EventBufferTest {

    private static final long LONG_INTERVAL = 60_000; // flush only when asked, keeps tests deterministic

    private static Map<String, Object> event(String id) {
        Map<String, Object> event = new HashMap<>();
        event.put("event_id", id);
        event.put("type", "exposure");
        return event;
    }

    /** In-memory offline store so spill behavior is testable without touching a filesystem clock. */
    private static final class RecordingStore implements OfflineStore {
        final List<Map<String, Object>> spilled = new CopyOnWriteArrayList<>();

        @Override
        public void appendAll(List<Map<String, Object>> events) {
            spilled.addAll(events);
        }

        @Override
        public List<Map<String, Object>> loadReplayable() {
            return List.copyOf(spilled);
        }

        @Override
        public void clear() {
            spilled.clear();
        }
    }

    @Test
    void addNeverBlocksOrThrowsAndDropsAfterExhaustedRetries() {
        EventBuffer buffer = new EventBuffer(
                batch -> {
                    throw new IOException("network down");
                },
                LONG_INTERVAL,
                1000,
                100_000,
                3,
                1,
                null);
        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            buffer.add(event("e" + i));
        }
        // 50 enqueue calls return effectively instantly — no network on the hot path.
        assertTrue(System.nanoTime() - started < 500_000_000L, "add() must not block");
        buffer.flush(2000);
        buffer.close(5000);
        // No offline store configured, so after exhausted retries the batch is dropped — not thrown.
        assertEquals(50, buffer.dropped());
    }

    @Test
    void spillsToOfflineStoreInsteadOfDropping() {
        RecordingStore store = new RecordingStore();
        EventBuffer buffer = new EventBuffer(
                batch -> {
                    throw new IOException("network down");
                },
                LONG_INTERVAL,
                1000,
                100_000,
                2,
                1,
                store);
        buffer.add(event("spilled"));
        buffer.flush(2000);
        buffer.close(5000);
        assertEquals(0, buffer.dropped());
        assertEquals(1, store.spilled.size());
        assertEquals("spilled", store.spilled.get(0).get("event_id"));
    }

    @Test
    void replaysOfflineEventsOnConstructionAndClearsTheStore() {
        RecordingStore store = new RecordingStore();
        store.appendAll(List.of(event("from-last-run")));
        List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();

        EventBuffer buffer = new EventBuffer(sent::addAll, LONG_INTERVAL, 1000, store);
        buffer.flush(2000);
        buffer.close(5000);

        assertEquals(1, sent.size());
        assertEquals("from-last-run", sent.get(0).get("event_id"));
        assertTrue(store.loadReplayable().isEmpty(), "store is cleared once replay is queued");
    }

    @Test
    void dropsOldestOnOverflow() {
        List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
        EventBuffer buffer = new EventBuffer(sent::addAll, LONG_INTERVAL, 1000, 10, 3, 1, null);
        for (int i = 0; i < 11; i++) {
            buffer.add(event("e" + i));
        }
        buffer.flush(2000);
        buffer.close(5000);
        assertEquals(1, buffer.dropped());
        assertEquals(10, sent.size());
        // The oldest event (e0) was evicted to make room; order is otherwise preserved.
        assertEquals("e1", sent.get(0).get("event_id"));
        assertEquals("e10", sent.get(9).get("event_id"));
    }

    @Test
    void sizeTriggerFlushesWithoutWaitingForTheInterval() {
        List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
        EventBuffer buffer = new EventBuffer(sent::addAll, LONG_INTERVAL, 3, null);
        buffer.add(event("a"));
        buffer.add(event("b"));
        buffer.add(event("c")); // hits flushSize — wakes the flusher immediately
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (sent.size() < 3 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        buffer.close(5000);
        assertEquals(3, sent.size());
    }
}

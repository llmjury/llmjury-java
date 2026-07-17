package com.llmjury.sdk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Bounded in-memory buffer with a single background flusher thread. {@code track} must never block
 * or throw into the host app's call path, so the public side is a non-blocking {@link #add} that
 * only appends to a deque;
 * all network I/O happens on a daemon thread that flushes on an interval (default 1s) or when the
 * buffer reaches a size threshold (default 1,000). A flush retries a bounded number of times and
 * then either spills to the {@link OfflineStore} (if configured) or drops the batch with a log line
 * — it never propagates an exception out. Behavioral port of the Python SDK's {@code EventBuffer}.
 */
public final class EventBuffer {

    /** Takes a batch and returns nothing; throws on a failed send so the buffer can retry. */
    public interface Sender {
        void send(List<Map<String, Object>> batch) throws Exception;
    }

    private final Sender sender;
    private final long flushIntervalMillis;
    private final int flushSize;
    private final int maxBuffered;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final OfflineStore offline;
    private final System.Logger log = System.getLogger("llmjury");

    private final Deque<Map<String, Object>> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private boolean wake;
    private volatile boolean stopped;
    private long dropped;

    private final Thread flusher;

    public EventBuffer(Sender sender, long flushIntervalMillis, int flushSize, OfflineStore offline) {
        this(sender, flushIntervalMillis, flushSize, 100_000, 3, 200, offline);
    }

    EventBuffer(
            Sender sender,
            long flushIntervalMillis,
            int flushSize,
            int maxBuffered,
            int maxRetries,
            long retryBackoffMillis,
            OfflineStore offline) {
        this.sender = sender;
        this.flushIntervalMillis = flushIntervalMillis;
        this.flushSize = flushSize;
        this.maxBuffered = maxBuffered;
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = retryBackoffMillis;
        this.offline = offline;

        // Replay anything spilled by a previous run before we start accepting new events.
        if (offline != null) {
            queue.addAll(offline.loadReplayable());
            offline.clear();
        }

        this.flusher = new Thread(this::run, "llmjury-flush");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    /** Total events dropped (buffer overflow or exhausted retries with no offline store). */
    public long dropped() {
        synchronized (lock) {
            return dropped;
        }
    }

    /** Enqueue an event. Non-blocking; never throws. Drops the oldest event on overflow. */
    public void add(Map<String, Object> event) {
        boolean due;
        synchronized (lock) {
            if (queue.size() >= maxBuffered) {
                queue.pollFirst();
                dropped++;
                log.log(
                        System.Logger.Level.WARNING,
                        "llmjury: event buffer full (" + maxBuffered + "); dropping oldest");
            }
            queue.addLast(event);
            due = queue.size() >= flushSize;
            if (due) {
                wake = true;
                lock.notifyAll();
            }
        }
    }

    /** Signal a flush and block (best-effort) until the buffer drains or the timeout elapses. */
    public void flush(long timeoutMillis) {
        synchronized (lock) {
            wake = true;
            lock.notifyAll();
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (lock) {
                if (queue.isEmpty()) {
                    return;
                }
            }
            sleep(10);
        }
    }

    /** Stop the flusher after a final drain. Safe to call more than once. */
    public void close(long timeoutMillis) {
        stopped = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            flusher.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (!stopped) {
            synchronized (lock) {
                if (!wake && !stopped) {
                    try {
                        lock.wait(flushIntervalMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                wake = false;
            }
            flushOnce();
        }
        flushOnce(); // final drain on shutdown
    }

    private void flushOnce() {
        while (true) {
            List<Map<String, Object>> batch = takeBatch();
            if (batch.isEmpty()) {
                return;
            }
            sendWithRetry(batch);
        }
    }

    private List<Map<String, Object>> takeBatch() {
        synchronized (lock) {
            int count = Math.min(flushSize, queue.size());
            List<Map<String, Object>> batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                batch.add(queue.pollFirst());
            }
            return batch;
        }
    }

    private void sendWithRetry(List<Map<String, Object>> batch) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                sender.send(batch);
                return;
            } catch (Exception exception) { // any transport error — retry, then spill/drop.
                if (attempt + 1 < maxRetries) {
                    sleep(retryBackoffMillis * (attempt + 1));
                    continue;
                }
                log.log(
                        System.Logger.Level.WARNING,
                        "llmjury: flush of " + batch.size() + " events failed after " + maxRetries + " attempts: "
                                + exception);
            }
        }
        if (offline != null) {
            offline.appendAll(batch);
        } else {
            synchronized (lock) {
                dropped += batch.size();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

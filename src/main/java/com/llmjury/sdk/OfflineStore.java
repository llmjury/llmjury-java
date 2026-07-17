package com.llmjury.sdk;

import java.util.List;
import java.util.Map;

/**
 * Durable spill+replay boundary for telemetry events. When the {@link EventBuffer} exhausts its
 * network retries it spills the batch here instead of dropping it, so events survive a process
 * restart or an extended outage; on the next client start they are replayed with their
 * <b>original</b> timestamps, bounded to a 24-hour age.
 *
 * <p>Implementations must never throw into the caller — I/O failures are logged and swallowed
 * (an SDK must never throw into the host app). Default: {@link FileOfflineStore}.
 */
public interface OfflineStore {

    /** Persist events for later replay, stamping each with the current wall-clock save time. */
    void appendAll(List<Map<String, Object>> events);

    /**
     * Return events younger than the 24-hour bound, in original order, with original timestamps.
     * Older records are silently dropped. Returns an empty list if there is nothing to replay.
     */
    List<Map<String, Object>> loadReplayable();

    /** Discard all persisted events (called after a successful replay). */
    void clear();
}

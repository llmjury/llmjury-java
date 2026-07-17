package com.llmjury.sdk;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Succeeds on every call and records what was sent; serves an optional fixed config. Counters are
 * atomic because the client's flush and poll threads call in concurrently.
 */
final class RecordingTransport implements Transport {

    private final List<CachedConfig> configs;
    final List<Map<String, Object>> sentEvents = new CopyOnWriteArrayList<>();
    final AtomicInteger configCalls = new AtomicInteger();
    final AtomicInteger eventCalls = new AtomicInteger();
    final AtomicLong ackConfigVersion = new AtomicLong();

    RecordingTransport() {
        this(Collections.emptyList());
    }

    RecordingTransport(List<CachedConfig> configs) {
        this.configs = configs;
    }

    @Override
    public ConfigResponse getConfig(String experimentId, String etag) {
        configCalls.incrementAndGet();
        if (configs.isEmpty()) {
            return ConfigResponse.notModified(etag);
        }
        return new ConfigResponse(200, configs, "etag-1");
    }

    @Override
    public IngestAck postEvents(List<Map<String, Object>> events) {
        eventCalls.incrementAndGet();
        sentEvents.addAll(events);
        return new IngestAck(events.size(), ackConfigVersion.get());
    }
}

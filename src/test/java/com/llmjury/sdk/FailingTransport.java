package com.llmjury.sdk;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Throws on every call — stands in for a dead network (no real network in tests). */
final class FailingTransport implements Transport {

    @Override
    public ConfigResponse getConfig(String experimentId, String etag) throws Exception {
        throw new IOException("network down");
    }

    @Override
    public IngestAck postEvents(List<Map<String, Object>> events) throws Exception {
        throw new IOException("network down");
    }
}

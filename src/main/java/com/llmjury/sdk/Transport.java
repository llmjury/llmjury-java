package com.llmjury.sdk;

import java.util.List;
import java.util.Map;

/**
 * Boundary the client talks to (interfaces at every external boundary). Tests pass
 * an in-process fake to exercise failure paths without a network; the default is
 * {@link HttpTransport}. Implementations throw on failure so the buffer/poller can retry — the
 * client guarantees nothing propagates into the host app.
 */
public interface Transport {

    /**
     * Fetch experiment config, optionally scoped to one experiment, with an ETag for a conditional
     * request.
     *
     * @param experimentId
     *            the experiment to fetch, or null for the org's full config
     * @param etag
     *            the last seen ETag (sent as {@code If-None-Match}), or null on the first fetch
     * @return the parsed response (200 with configs, or 304)
     * @throws Exception
     *             on any transport or protocol failure
     */
    ConfigResponse getConfig(String experimentId, String etag) throws Exception;

    /**
     * Send a batch of events to {@code POST /v1/events}.
     *
     * @param events
     *            the event records, already stamped with {@code event_id} and {@code timestamp}
     * @return the ingest ack (accepted count + config version staleness hint)
     * @throws Exception
     *             on any transport or protocol failure
     */
    IngestAck postEvents(List<Map<String, Object>> events) throws Exception;
}

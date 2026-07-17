package com.llmjury.sdk;

import java.util.Collections;
import java.util.List;

/** Result of a {@code GET /v1/config} poll. {@code status} is 200 (configs) or 304 (unchanged). */
public final class ConfigResponse {

    private final int status;
    private final List<CachedConfig> configs;
    private final String etag;

    public ConfigResponse(int status, List<CachedConfig> configs, String etag) {
        this.status = status;
        this.configs = Collections.unmodifiableList(configs);
        this.etag = etag;
    }

    /** A 304 Not Modified response: the cached config (and its ETag) stay as they are. */
    public static ConfigResponse notModified(String etag) {
        return new ConfigResponse(304, Collections.emptyList(), etag);
    }

    public int status() {
        return status;
    }

    public List<CachedConfig> configs() {
        return configs;
    }

    /** The response ETag to send back as {@code If-None-Match} on the next poll; may be null. */
    public String etag() {
        return etag;
    }
}

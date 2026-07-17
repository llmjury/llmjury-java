package com.llmjury.sdk;

/**
 * Result of {@code POST /v1/events}. {@code configVersion} is the org-aggregate staleness hint —
 * when it is newer than what the client holds, an immediate config refresh is triggered
 * (config distribution).
 */
public final class IngestAck {

    private final long accepted;
    private final long configVersion;

    public IngestAck(long accepted, long configVersion) {
        this.accepted = accepted;
        this.configVersion = configVersion;
    }

    public long accepted() {
        return accepted;
    }

    public long configVersion() {
        return configVersion;
    }
}

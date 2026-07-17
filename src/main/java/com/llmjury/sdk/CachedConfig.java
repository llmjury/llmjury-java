package com.llmjury.sdk;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** The bucketing inputs the SDK needs, distilled from a fetched experiment config. Immutable. */
public final class CachedConfig {

    private final String experimentId;
    private final String salt;
    private final int bucketCount;
    private final List<AllocationSlice> allocation;
    private final long version;
    private final String name;

    public CachedConfig(
            String experimentId, String salt, int bucketCount, List<AllocationSlice> allocation, long version) {
        this(experimentId, salt, bucketCount, allocation, version, null);
    }

    /** @param name the unique, human-readable experiment name (an alternate address), or null */
    public CachedConfig(
            String experimentId,
            String salt,
            int bucketCount,
            List<AllocationSlice> allocation,
            long version,
            String name) {
        this.experimentId = Objects.requireNonNull(experimentId, "experimentId");
        this.salt = Objects.requireNonNull(salt, "salt");
        this.bucketCount = bucketCount;
        this.allocation = Collections.unmodifiableList(allocation);
        this.version = version;
        this.name = name;
    }

    public String experimentId() {
        return experimentId;
    }

    public String salt() {
        return salt;
    }

    public int bucketCount() {
        return bucketCount;
    }

    public List<AllocationSlice> allocation() {
        return allocation;
    }

    public long version() {
        return version;
    }

    /** @return the unique experiment name, or null when the payload carried none */
    public String name() {
        return name;
    }
}

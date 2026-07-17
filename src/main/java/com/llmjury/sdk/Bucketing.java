package com.llmjury.sdk;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The frozen, language-agnostic bucketing hash (spec/bucketing.md): MurmurHash3 x86 32-bit,
 * seed fixed at 0, key = UTF-8 of {@code "{salt}:{user_id}:{experiment_id}"}, mapped onto
 * integer-only cumulative allocation boundaries.
 *
 * <p>This MUST stay bit-for-bit identical to the Python and TypeScript SDKs and the backend
 * assignment service: the same {@code (salt, user_id, experiment_id, bucket_count, allocation)}
 * always resolves to the same variant. The cross-language determinism fixture
 * {@code spec/fixtures/bucketing-cases.json} is the merge gate; changing any rule here is a
 * breaking contract change.
 *
 * <p>Intentionally integer-only so there is no floating-point divergence across languages.
 */
public final class Bucketing {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private Bucketing() {}

    /**
     * MurmurHash3 x86 32-bit (Austin Appleby). Java {@code int} arithmetic is two's-complement mod
     * 2^32, matching the reference implementation's explicit 32-bit masking; the result is the raw
     * 32-bit hash (treat as unsigned via {@link Integer#remainderUnsigned}).
     *
     * @param data
     *            input bytes
     * @param seed
     *            hash seed (0 in v1)
     * @return the 32-bit hash
     */
    public static int murmur3X8632(byte[] data, int seed) {
        int h1 = seed;
        int length = data.length;
        int roundedEnd = length & ~0x03; // largest multiple of 4 <= length

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tail = length & 0x03;
        if (tail == 3) {
            k1 ^= (data[roundedEnd + 2] & 0xff) << 16;
        }
        if (tail >= 2) {
            k1 ^= (data[roundedEnd + 1] & 0xff) << 8;
        }
        if (tail >= 1) {
            k1 ^= data[roundedEnd] & 0xff;
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
        }

        h1 ^= length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    /**
     * Map a user to a bucket in {@code [0, bucketCount)} per spec/bucketing.md §2.
     *
     * @param salt
     *            per-experiment salt (pinned per experiment version)
     * @param userId
     *            stable user identifier
     * @param experimentId
     *            experiment identifier
     * @param bucketCount
     *            per-experiment bucket count carried in the fetched config (default 1,000) — never
     *            a hardcoded modulus in the SDK
     * @return the bucket index
     */
    public static int bucketOf(String salt, String userId, String experimentId, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive, got " + bucketCount);
        }
        byte[] key = (salt + ":" + userId + ":" + experimentId).getBytes(StandardCharsets.UTF_8);
        int hash = murmur3X8632(key, 0);
        return Integer.remainderUnsigned(hash, bucketCount);
    }

    /**
     * Deterministically assign a variant key using integer-only cumulative boundaries
     * (spec/bucketing.md §3). The boundary for slice {@code i} is
     * {@code floor(cumulative_weight_i * bucket_count / total_weight)}; a bucket belongs to the
     * first slice whose boundary it falls below, and the final slice absorbs any remainder so every
     * bucket maps to exactly one variant.
     *
     * @param salt
     *            per-experiment salt
     * @param userId
     *            stable user identifier
     * @param experimentId
     *            experiment identifier
     * @param bucketCount
     *            per-experiment bucket count from the fetched config
     * @param allocation
     *            the ordered allocation slices (must be non-empty with a positive total weight)
     * @return the assigned variant key
     */
    public static String assignVariant(
            String salt, String userId, String experimentId, int bucketCount, List<AllocationSlice> allocation) {
        if (allocation == null || allocation.isEmpty()) {
            throw new IllegalArgumentException("allocation must not be empty");
        }
        int bucket = bucketOf(salt, userId, experimentId, bucketCount);
        long total = 0;
        for (AllocationSlice slice : allocation) {
            total += slice.weight();
        }
        if (total <= 0) {
            throw new IllegalArgumentException("allocation total weight must be positive, got " + total);
        }
        long cumulative = 0;
        for (AllocationSlice slice : allocation) {
            cumulative += slice.weight();
            long boundary = cumulative * bucketCount / total;
            if (bucket < boundary) {
                return slice.variant();
            }
        }
        return allocation.get(allocation.size() - 1).variant();
    }
}

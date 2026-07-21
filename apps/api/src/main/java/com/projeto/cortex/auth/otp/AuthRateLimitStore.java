package com.projeto.cortex.auth.otp;

import java.time.Instant;
import java.util.List;

/** Persistence boundary for bounded, shared authentication rate-limit buckets. */
public interface AuthRateLimitStore {

    boolean hasCapacity(String bucketKey, int maxRequests, int windowSeconds);

    boolean consume(List<String> bucketKeys, int maxRequests, int windowSeconds);

    record Bucket(
            String key,
            Instant windowStartedAt,
            int counter,
            Instant blockedUntil,
            Instant now
    ) {
    }
}

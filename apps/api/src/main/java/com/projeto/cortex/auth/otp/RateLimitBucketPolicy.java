package com.projeto.cortex.auth.otp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class RateLimitBucketPolicy {

    private RateLimitBucketPolicy() {
    }

    static List<String> normalizeKeys(List<String> bucketKeys) {
        if (bucketKeys == null) {
            throw invalidKeys();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(bucketKeys);
        if (unique.isEmpty() || unique.size() > 3 || unique.stream().anyMatch(
                key -> key == null || !key.matches("[0-9a-f]{64}"))) {
            throw invalidKeys();
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    static void validatePolicy(int maxRequests, int windowSeconds) {
        if (maxRequests < 1 || maxRequests > 100_000
                || windowSeconds < 60 || windowSeconds > 3_600) {
            throw new IllegalArgumentException("Política de rate limit inválida.");
        }
    }

    static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static IllegalArgumentException invalidKeys() {
        return new IllegalArgumentException("Buckets de rate limit inválidos.");
    }
}

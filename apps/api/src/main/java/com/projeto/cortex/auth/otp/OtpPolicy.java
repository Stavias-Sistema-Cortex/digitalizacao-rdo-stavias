package com.projeto.cortex.auth.otp;

/** Validated security and throttling bounds for e-mail challenges. */
public record OtpPolicy(
        int ttlSeconds,
        int maxAttempts,
        int rateLimitMaxRequests,
        int globalRateLimitMaxRequests,
        int rateLimitWindowSeconds
) {

    public OtpPolicy {
        if (ttlSeconds < 60
                || ttlSeconds > 1_800
                || maxAttempts < 1
                || maxAttempts > 10
                || rateLimitMaxRequests < 1
                || rateLimitMaxRequests > 20
                || globalRateLimitMaxRequests < rateLimitMaxRequests
                || globalRateLimitMaxRequests > 100_000
                || rateLimitWindowSeconds < 60
                || rateLimitWindowSeconds > 3_600) {
            throw new IllegalStateException(
                    "Política de autenticação por e-mail inválida."
            );
        }
    }
}

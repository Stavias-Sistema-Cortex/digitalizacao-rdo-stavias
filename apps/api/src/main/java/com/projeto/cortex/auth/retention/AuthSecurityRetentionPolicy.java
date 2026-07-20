package com.projeto.cortex.auth.retention;

/** Validated limits for one bounded authentication-data retention run. */
public record AuthSecurityRetentionPolicy(
        int challengeRetentionSeconds,
        int rateLimitRetentionSeconds,
        int batchSize,
        long initialDelayMillis,
        long fixedDelayMillis
) {

    private static final int MIN_CHALLENGE_RETENTION_SECONDS = 60;
    private static final int MAX_CHALLENGE_RETENTION_SECONDS = 31_536_000;
    private static final int MIN_RATE_LIMIT_RETENTION_SECONDS = 3_600;
    private static final int MAX_RATE_LIMIT_RETENTION_SECONDS = 2_592_000;
    private static final int MAX_BATCH_SIZE = 5_000;
    private static final long MIN_SCHEDULE_DELAY_MILLIS = 60_000;
    private static final long MAX_SCHEDULE_DELAY_MILLIS = 86_400_000;

    public AuthSecurityRetentionPolicy {
        if (challengeRetentionSeconds < MIN_CHALLENGE_RETENTION_SECONDS
                || challengeRetentionSeconds
                > MAX_CHALLENGE_RETENTION_SECONDS
                || rateLimitRetentionSeconds
                < MIN_RATE_LIMIT_RETENTION_SECONDS
                || rateLimitRetentionSeconds
                > MAX_RATE_LIMIT_RETENTION_SECONDS
                || batchSize < 1
                || batchSize > MAX_BATCH_SIZE
                || initialDelayMillis < MIN_SCHEDULE_DELAY_MILLIS
                || initialDelayMillis > MAX_SCHEDULE_DELAY_MILLIS
                || fixedDelayMillis < MIN_SCHEDULE_DELAY_MILLIS
                || fixedDelayMillis > MAX_SCHEDULE_DELAY_MILLIS) {
            throw new IllegalArgumentException(
                    "Política de retenção de autenticação inválida."
            );
        }
    }
}

package com.projeto.cortex.auth.password;

import java.time.Instant;

public record LockedPasswordSetupChallenge(
        String challengeId,
        String collaboratorId,
        String codeDigest,
        Instant expiresAt,
        int attempts,
        int maxAttempts,
        String status,
        Instant databaseNow
) {
}

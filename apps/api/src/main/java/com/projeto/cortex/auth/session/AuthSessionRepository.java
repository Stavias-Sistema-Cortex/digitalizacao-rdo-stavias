package com.projeto.cortex.auth.session;

import java.time.Instant;
import java.util.Optional;

/** Persistence boundary for opaque, revocable authentication sessions. */
public interface AuthSessionRepository {

    Instant create(
            String sessionId,
            String collaboratorId,
            String tokenHash,
            String csrfHash,
            int ttlSeconds
    );

    Optional<ResolvedAuthSession> findActiveByTokenHash(String tokenHash);

    int revokeByTokenHash(String tokenHash, String reason);
}

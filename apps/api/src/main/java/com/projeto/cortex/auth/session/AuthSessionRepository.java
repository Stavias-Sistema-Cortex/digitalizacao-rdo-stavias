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
            String clientInstanceHash,
            int ttlSeconds
    );

    Optional<ResolvedAuthSession> findActiveByTokenHashAndClientInstanceHash(
            String tokenHash,
            String clientInstanceHash
    );

    int revokeByTokenHash(String tokenHash, String reason);
}

package com.projeto.cortex.auth.password;

import java.time.Instant;
import java.util.Optional;

public interface PasswordSetupStore {

    boolean canManageAccess(String actorId);

    Optional<PasswordSetupTarget> findActiveAcademyTarget(
            String collaboratorId
    );

    int invalidatePending(String collaboratorId, String reason);

    Instant create(
            String challengeId,
            String collaboratorId,
            String codeDigest,
            PasswordSetupPurpose purpose,
            String actorId,
            int ttlSeconds,
            int maxAttempts
    );

    Optional<LockedPasswordSetupChallenge> lockLatestPending(
            String collaboratorId
    );

    int markExpired(String challengeId);

    int block(String challengeId);

    int recordFailedAttempt(String challengeId);

    int activateIdentity(String collaboratorId);

    int consume(String challengeId);

    void audit(
            String eventType,
            String actorId,
            String collaboratorId,
            String challengeId
    );
}

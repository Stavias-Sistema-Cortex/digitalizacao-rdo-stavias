package com.projeto.cortex.auth.otp;

import java.time.Instant;
import java.util.Optional;

/** Persistence contract for a single-use e-mail OTP challenge. */
public interface EmailOtpChallengeStore {

    Instant create(
            String challengeId,
            String collaboratorId,
            String identifierDigest,
            String codeDigest,
            String clientInstanceHash,
            int ttlSeconds,
            int maxAttempts
    );

    Optional<LockedChallenge> lockForVerification(
            String challengeId,
            String clientInstanceHash
    );

    Optional<DeliveryState> deliveryState(String challengeId);

    int invalidateUndelivered(String challengeId);

    int markExpired(String challengeId);

    int block(String challengeId);

    int recordFailedAttempt(String challengeId);

    int consume(
            String challengeId,
            String collaboratorId,
            String codeDigest,
            String clientInstanceHash
    );

    int activateIdentity(String collaboratorId, String authenticationEmail);

    record LockedChallenge(
            String challengeId,
            String collaboratorId,
            String codeDigest,
            Instant expiresAt,
            int attempts,
            int maxAttempts,
            String challengeStatus,
            Instant databaseNow,
            String authenticationEmail,
            String collaboratorName,
            String persistedRole,
            String identityStatus,
            boolean collaboratorActive,
            boolean collaboratorDeleted
    ) {

        public LockedChallenge {
            if (challengeId == null
                    || codeDigest == null
                    || expiresAt == null
                    || challengeStatus == null
                    || databaseNow == null) {
                throw new IllegalArgumentException(
                        "Desafio de autenticação inválido."
                );
            }
        }
    }

    record DeliveryState(
            String status,
            Instant expiresAt,
            Instant databaseNow
    ) {

        public DeliveryState {
            if (status == null
                    || expiresAt == null
                    || databaseNow == null) {
                throw new IllegalArgumentException(
                        "Estado de entrega de autenticação inválido."
                );
            }
        }

        public boolean pending() {
            return "PENDENTE".equals(status);
        }

        public boolean expired() {
            return !databaseNow.isBefore(expiresAt);
        }
    }
}

package com.projeto.cortex.auth.otp;

import com.projeto.cortex.email.EmailMessage;
import java.time.Instant;
import java.util.UUID;

/** In-memory post-commit signal whose string form never contains secrets. */
public final class OtpDeliveryRequested {

    private final String challengeId;
    private final String recipient;
    private final String code;
    private final Instant expiresAt;
    private final boolean deliverable;

    private OtpDeliveryRequested(
            String challengeId,
            String recipient,
            String code,
            Instant expiresAt,
            boolean deliverable
    ) {
        this.challengeId = challengeId;
        this.recipient = recipient;
        this.code = code;
        this.expiresAt = expiresAt;
        this.deliverable = deliverable;
    }

    public static OtpDeliveryRequested deliverable(
            String challengeId,
            String recipient,
            String code,
            Instant expiresAt
    ) {
        if (!canonicalUuid(challengeId)
                || !EmailMessage.isValidRecipient(recipient)
                || code == null
                || !code.matches("[0-9]{6}")
                || expiresAt == null) {
            throw new IllegalArgumentException(
                    "Entrega de autenticação inválida."
            );
        }
        return new OtpDeliveryRequested(
                challengeId,
                recipient.strip(),
                code,
                expiresAt,
                true
        );
    }

    public static OtpDeliveryRequested decoy() {
        return new OtpDeliveryRequested(null, null, null, null, false);
    }

    public boolean deliverable() {
        return deliverable;
    }

    String challengeId() {
        return challengeId;
    }

    String recipient() {
        return recipient;
    }

    String code() {
        return code;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "OtpDeliveryRequested[redacted]";
    }

    private static boolean canonicalUuid(String value) {
        try {
            return value != null
                    && UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

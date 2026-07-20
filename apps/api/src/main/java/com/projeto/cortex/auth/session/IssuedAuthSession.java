package com.projeto.cortex.auth.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Raw credentials returned once so the HTTP boundary can set secure cookies. */
public record IssuedAuthSession(
        String sessionId,
        String sessionToken,
        String csrfToken,
        Instant expiresAt
) {

    public IssuedAuthSession {
        sessionId = canonicalUuid(sessionId);
        sessionToken = requireRawToken(sessionToken);
        csrfToken = requireRawToken(csrfToken);
        expiresAt = Objects.requireNonNull(
                expiresAt,
                "Expiração da sessão obrigatória."
        );
        if (sessionToken.equals(csrfToken)) {
            throw new IllegalArgumentException(
                    "Credenciais de sessão devem ser independentes."
            );
        }
    }

    @Override
    public String toString() {
        return "IssuedAuthSession[sessionId=" + sessionId
                + ", sessionToken=[REDACTED]"
                + ", csrfToken=[REDACTED]"
                + ", expiresAt=" + expiresAt + "]";
    }

    static String requireRawToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException(
                    "Credencial de sessão inválida."
            );
        }
        return value;
    }

    static String canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Identificador de sessão inválido."
            );
        }
    }
}

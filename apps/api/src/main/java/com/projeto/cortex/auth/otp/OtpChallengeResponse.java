package com.projeto.cortex.auth.otp;

import java.util.UUID;

/** Enumeration-safe response shared by every logical identifier outcome. */
public record OtpChallengeResponse(
        String challengeId,
        int expiresInSeconds,
        String message
) {

    private static final String GENERIC_MESSAGE =
            "Se os dados estiverem aptos, enviaremos um código para o e-mail cadastrado.";

    public static OtpChallengeResponse generic(
            String challengeId,
            int expiresInSeconds
    ) {
        if (!isCanonicalUuid(challengeId) || expiresInSeconds < 1) {
            throw new IllegalArgumentException(
                    "Resposta de autenticação inválida."
            );
        }
        return new OtpChallengeResponse(
                challengeId,
                expiresInSeconds,
                GENERIC_MESSAGE
        );
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return value != null
                    && UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

package com.projeto.cortex.auth.webauthn;

/** Bounded shared-store throttling policy for anonymous passkey endpoints. */
public record WebAuthnRateLimitPolicy(
        int maxRequests,
        int globalMaxRequests,
        int windowSeconds
) {

    public WebAuthnRateLimitPolicy {
        if (maxRequests < 1
                || maxRequests > 1_000
                || globalMaxRequests < maxRequests
                || globalMaxRequests > 100_000
                || windowSeconds < 60
                || windowSeconds > 3_600) {
            throw new IllegalStateException(
                    "Política de rate limit WebAuthn inválida."
            );
        }
    }
}

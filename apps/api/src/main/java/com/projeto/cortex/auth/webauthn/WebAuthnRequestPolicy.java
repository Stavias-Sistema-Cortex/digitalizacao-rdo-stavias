package com.projeto.cortex.auth.webauthn;

/** Bounded request policy applied before Spring MVC deserializes JSON. */
public record WebAuthnRequestPolicy(int maxBodyBytes) {

    public WebAuthnRequestPolicy {
        if (maxBodyBytes < 4_096 || maxBodyBytes > 262_144) {
            throw new IllegalArgumentException(
                    "Limite de payload WebAuthn inválido."
            );
        }
    }
}

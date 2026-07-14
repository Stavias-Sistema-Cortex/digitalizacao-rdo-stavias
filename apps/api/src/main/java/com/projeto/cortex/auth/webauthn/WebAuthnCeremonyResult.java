package com.projeto.cortex.auth.webauthn;

import com.fasterxml.jackson.databind.JsonNode;

/** Raw browser ceremony response. Its string representation is always redacted. */
public record WebAuthnCeremonyResult(
        String challengeId,
        JsonNode credential
) {

    @Override
    public String toString() {
        return "WebAuthnCeremonyResult[credential=[REDACTED]]";
    }
}

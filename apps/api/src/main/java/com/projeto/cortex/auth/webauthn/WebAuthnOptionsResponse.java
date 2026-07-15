package com.projeto.cortex.auth.webauthn;

import com.fasterxml.jackson.databind.JsonNode;

/** Browser-facing public-key options plus server-side challenge reference. */
public record WebAuthnOptionsResponse(
        String challengeId,
        JsonNode publicKey,
        String rpId
) {

    @Override
    public String toString() {
        return "WebAuthnOptionsResponse[publicKey=[REDACTED]]";
    }
}

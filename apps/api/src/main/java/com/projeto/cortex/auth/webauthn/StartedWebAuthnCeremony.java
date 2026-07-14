package com.projeto.cortex.auth.webauthn;

import com.fasterxml.jackson.databind.JsonNode;
import com.yubico.webauthn.data.ByteArray;

record StartedWebAuthnCeremony(
        ByteArray challenge,
        String requestJson,
        JsonNode publicKey
) {

    StartedWebAuthnCeremony {
        if (challenge == null
                || challenge.isEmpty()
                || requestJson == null
                || requestJson.isBlank()
                || publicKey == null
                || !publicKey.isObject()) {
            throw new IllegalArgumentException(
                    "Opções WebAuthn inválidas."
            );
        }
    }

    @Override
    public String toString() {
        return "StartedWebAuthnCeremony[request=[REDACTED]]";
    }
}

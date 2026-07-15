package com.projeto.cortex.auth.webauthn;

record StoredWebAuthnChallenge(
        String id,
        String collaboratorId,
        WebAuthnCeremony ceremony,
        String requestJson
) {

    @Override
    public String toString() {
        return "StoredWebAuthnChallenge[id=" + id
                + ", ceremony=" + ceremony
                + ", requestJson=[REDACTED]]";
    }
}

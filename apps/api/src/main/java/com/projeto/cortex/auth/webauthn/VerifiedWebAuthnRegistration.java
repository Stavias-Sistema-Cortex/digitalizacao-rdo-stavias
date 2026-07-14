package com.projeto.cortex.auth.webauthn;

record VerifiedWebAuthnRegistration(
        NewWebAuthnCredential credential,
        boolean prfSupported
) {

    @Override
    public String toString() {
        return "VerifiedWebAuthnRegistration[credential=[REDACTED]]";
    }
}

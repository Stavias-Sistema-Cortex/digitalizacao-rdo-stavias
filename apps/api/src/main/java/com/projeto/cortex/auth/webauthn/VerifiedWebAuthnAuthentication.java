package com.projeto.cortex.auth.webauthn;

import com.yubico.webauthn.data.ByteArray;

record VerifiedWebAuthnAuthentication(
        String collaboratorId,
        ByteArray userHandle,
        ByteArray credentialId,
        long signatureCount,
        boolean backedUp
) {

    @Override
    public String toString() {
        return "VerifiedWebAuthnAuthentication[credential=[REDACTED]]";
    }
}

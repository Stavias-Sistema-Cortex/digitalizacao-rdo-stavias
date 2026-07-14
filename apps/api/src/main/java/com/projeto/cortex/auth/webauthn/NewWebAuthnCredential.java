package com.projeto.cortex.auth.webauthn;

import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import java.util.Objects;
import java.util.Set;

record NewWebAuthnCredential(
        ByteArray credentialId,
        ByteArray userHandle,
        ByteArray publicKeyCose,
        long signatureCount,
        Set<AuthenticatorTransport> transports,
        String aaguid,
        boolean discoverable,
        boolean backedUp
) {

    NewWebAuthnCredential {
        Objects.requireNonNull(credentialId, "Credential ID obrigatório.");
        Objects.requireNonNull(userHandle, "User handle obrigatório.");
        Objects.requireNonNull(publicKeyCose, "Chave pública obrigatória.");
        if (credentialId.isEmpty()
                || credentialId.size() > 1_024
                || userHandle.size() != 16
                || publicKeyCose.isEmpty()
                || publicKeyCose.size() > 65_535
                || signatureCount < 0
                || !discoverable
                || !isCanonicalUuid(aaguid)) {
            throw new IllegalArgumentException(
                    "Material de passkey inválido."
            );
        }
        transports = transports == null ? Set.of() : Set.copyOf(transports);
        if (transports.size() > 16) {
            throw new IllegalArgumentException(
                    "Transportes de passkey inválidos."
            );
        }
    }

    @Override
    public String toString() {
        return "NewWebAuthnCredential[credential=[REDACTED]]";
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return java.util.UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

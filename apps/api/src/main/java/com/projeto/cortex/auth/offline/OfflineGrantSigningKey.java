package com.projeto.cortex.auth.offline;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

record OfflineGrantSigningKey(
        String keyId,
        PrivateKey privateKey,
        PublicKey publicKey
) {

    OfflineGrantSigningKey {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("keyId de grant offline inválido.");
        }
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(publicKey, "publicKey");
    }

    @Override
    public String toString() {
        return "OfflineGrantSigningKey[keyId=" + keyId
                + ", keyMaterial=[REDACTED]]";
    }
}

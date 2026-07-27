package com.projeto.cortex.ontology;

import java.util.Arrays;

final class OperationalMemoryCursorKeyring {

    private static final int MINIMUM_KEY_BYTES = 32;

    private final String currentKeyId;
    private final byte[] currentKey;
    private final String previousKeyId;
    private final byte[] previousKey;

    OperationalMemoryCursorKeyring(
            String currentKeyId,
            byte[] currentKey,
            String previousKeyId,
            byte[] previousKey
    ) {
        this.currentKeyId = requireKeyId(currentKeyId);
        this.currentKey = requireKey(currentKey, "atual");
        boolean hasPreviousId = previousKeyId != null && !previousKeyId.isBlank();
        boolean hasPreviousKey = previousKey != null;
        if (hasPreviousId != hasPreviousKey) {
            throw new IllegalStateException(
                    "A rotação do cursor de Memória exige ID e chave anterior."
            );
        }
        if (!hasPreviousId) {
            this.previousKeyId = null;
            this.previousKey = null;
            return;
        }
        this.previousKeyId = requireKeyId(previousKeyId);
        this.previousKey = requireKey(previousKey, "anterior");
        if (this.currentKeyId.equals(this.previousKeyId)) {
            throw new IllegalStateException(
                    "Os IDs das chaves atual e anterior do cursor devem ser distintos."
            );
        }
        if (Arrays.equals(this.currentKey, this.previousKey)) {
            throw new IllegalStateException(
                    "As chaves atual e anterior do cursor devem usar materiais distintos."
            );
        }
    }

    String currentKeyId() {
        return currentKeyId;
    }

    byte[] currentKey() {
        return currentKey.clone();
    }

    byte[] keyFor(String keyId) {
        if (currentKeyId.equals(keyId)) {
            return currentKey.clone();
        }
        if (previousKeyId != null && previousKeyId.equals(keyId)) {
            return previousKey.clone();
        }
        throw new OperationalMemoryCursorScopeException();
    }

    private String requireKeyId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalStateException("Key ID do cursor de Memória inválido.");
        }
        return value;
    }

    private byte[] requireKey(byte[] value, String label) {
        if (value == null || value.length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "A chave " + label + " do cursor de Memória deve ter ao menos 32 bytes."
            );
        }
        return value.clone();
    }

    @Override
    public String toString() {
        return "OperationalMemoryCursorKeyring[currentKeyId=" + currentKeyId
                + ", previousKeyId=" + previousKeyId
                + ", keyMaterial=[REDACTED]]";
    }
}

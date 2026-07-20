package com.projeto.cortex.auth.identity;

/** A versioned, keyed CPF lookup digest. It is never an authentication proof. */
public record CpfLookupDigest(String keyId, String value) {

    public CpfLookupDigest {
        if (keyId == null
                || !keyId.matches("[A-Za-z0-9._-]{1,32}")
                || value == null
                || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Digest de CPF inválido.");
        }
    }
}

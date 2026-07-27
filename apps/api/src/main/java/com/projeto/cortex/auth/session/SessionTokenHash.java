package com.projeto.cortex.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** One-way SHA-256 representation used for opaque session material. */
final class SessionTokenHash {

    private static final int MAXIMUM_MATERIAL_LENGTH = 4_096;

    private SessionTokenHash() {
    }

    static String sha256(String rawMaterial) {
        byte[] digest = digest(rawMaterial);
        try {
            return HexFormat.of().formatHex(digest);
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    static boolean matches(String rawCandidate, String storedHash) {
        if (!isAcceptableMaterial(rawCandidate)
                || storedHash == null
                || !storedHash.matches("[0-9a-f]{64}")) {
            return false;
        }
        byte[] candidate = digest(rawCandidate);
        byte[] stored = HexFormat.of().parseHex(storedHash);
        try {
            return MessageDigest.isEqual(candidate, stored);
        } finally {
            Arrays.fill(candidate, (byte) 0);
            Arrays.fill(stored, (byte) 0);
        }
    }

    /** Compares two persisted SHA-256 values without timing-dependent equals. */
    static boolean matchesHash(String candidateHash, String storedHash) {
        if (!isHash(candidateHash) || !isHash(storedHash)) {
            return false;
        }
        byte[] candidate = HexFormat.of().parseHex(candidateHash);
        byte[] stored = HexFormat.of().parseHex(storedHash);
        try {
            return MessageDigest.isEqual(candidate, stored);
        } finally {
            Arrays.fill(candidate, (byte) 0);
            Arrays.fill(stored, (byte) 0);
        }
    }

    private static byte[] digest(String rawMaterial) {
        if (!isAcceptableMaterial(rawMaterial)) {
            throw new IllegalArgumentException(
                    "Material de sessão inválido."
            );
        }
        byte[] bytes = rawMaterial.getBytes(StandardCharsets.US_ASCII);
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 indisponível para autenticação.",
                    exception
            );
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean isAcceptableMaterial(String rawMaterial) {
        return rawMaterial != null
                && !rawMaterial.isBlank()
                && rawMaterial.length() <= MAXIMUM_MATERIAL_LENGTH
                && rawMaterial.chars().allMatch(value -> value <= 0x7f);
    }

    private static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}

package com.projeto.cortex.auth.otp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Generates OTPs and produces only domain-separated HMAC verifiers. */
public final class OtpCryptography {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] CODE_DOMAIN =
            "cortex:auth-email-code:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BUCKET_DOMAIN =
            "cortex:auth-rate-bucket:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IDENTIFIER_DOMAIN =
            "cortex:auth-challenge-identifier:v1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DECOY_CODE_DOMAIN =
            "cortex:auth-email-decoy-code:v1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> BUCKET_DIMENSIONS = Set.of(
            "identifier",
            "ip",
            "global"
    );

    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public OtpCryptography(byte[] keyMaterial, SecureRandom secureRandom) {
        if (keyMaterial == null
                || keyMaterial.length < 32
                || secureRandom == null) {
            throw invalidInput();
        }
        byte[] keyCopy = Arrays.copyOf(keyMaterial, keyMaterial.length);
        try {
            this.key = new SecretKeySpec(keyCopy, ALGORITHM);
        } finally {
            Arrays.fill(keyCopy, (byte) 0);
        }
        this.secureRandom = secureRandom;
    }

    public String generateCode() {
        return String.format(
                Locale.ROOT,
                "%06d",
                secureRandom.nextInt(1_000_000)
        );
    }

    public String codeDigest(
            String challengeId,
            String code,
            String authenticationEmail
    ) {
        requireChallengeAndCode(challengeId, code);
        String email = normalize(authenticationEmail, 320);
        if (email == null) {
            throw invalidInput();
        }
        return hmac(
                CODE_DOMAIN,
                challengeId,
                code,
                email.toLowerCase(Locale.ROOT)
        );
    }

    public String decoyCodeDigest(String challengeId, String code) {
        requireChallengeAndCode(challengeId, code);
        return hmac(DECOY_CODE_DOMAIN, challengeId, code);
    }

    public String identifierDigest(String identifier) {
        return hmac(
                IDENTIFIER_DOMAIN,
                AuthRequestNormalizer.identifier(identifier)
        );
    }

    public boolean matchesDigest(String candidate, String stored) {
        if (candidate == null
                || stored == null
                || !candidate.matches("[0-9a-f]{64}")
                || !stored.matches("[0-9a-f]{64}")) {
            return false;
        }
        byte[] candidateBytes = HexFormat.of().parseHex(candidate);
        byte[] storedBytes = HexFormat.of().parseHex(stored);
        try {
            return MessageDigest.isEqual(candidateBytes, storedBytes);
        } finally {
            Arrays.fill(candidateBytes, (byte) 0);
            Arrays.fill(storedBytes, (byte) 0);
        }
    }

    public String bucketDigest(String dimension, String value) {
        if (!BUCKET_DIMENSIONS.contains(dimension)) {
            throw invalidInput();
        }
        String normalized = normalize(value, 512);
        if (normalized == null) {
            throw invalidInput();
        }
        return hmac(BUCKET_DOMAIN, dimension, normalized);
    }

    private String hmac(byte[] domain, String... components) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(domain);
            mac.update((byte) 0);
            for (String component : components) {
                byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
                try {
                    mac.update(ByteBuffer.allocate(Integer.BYTES)
                            .putInt(bytes.length)
                            .array());
                    mac.update(bytes);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "HMAC indisponível para autenticação."
            );
        }
    }

    private void requireChallengeAndCode(
            String challengeId,
            String code
    ) {
        try {
            UUID parsed = UUID.fromString(challengeId);
            if (!parsed.toString().equals(challengeId)) {
                throw invalidInput();
            }
        } catch (RuntimeException exception) {
            throw invalidInput();
        }
        if (code == null || !code.matches("[0-9]{6}")) {
            throw invalidInput();
        }
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength
                || normalized.contains("\r")
                || normalized.contains("\n")) {
            return null;
        }
        return normalized;
    }

    private IllegalArgumentException invalidInput() {
        return new IllegalArgumentException(
                "Material de autenticação inválido."
        );
    }
}

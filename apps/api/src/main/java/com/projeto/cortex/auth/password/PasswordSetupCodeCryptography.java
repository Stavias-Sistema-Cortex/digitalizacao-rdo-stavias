package com.projeto.cortex.auth.password;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Generates a short-lived handoff code and stores only its HMAC digest. */
public final class PasswordSetupCodeCryptography {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DOMAIN = "cortex-password-setup-v1";
    private static final int CODE_SPACE = 100_000_000;

    private final byte[] key;
    private final SecureRandom random;

    public PasswordSetupCodeCryptography(
            byte[] keyMaterial,
            SecureRandom random
    ) {
        if (keyMaterial == null || keyMaterial.length < 32) {
            throw new IllegalArgumentException(
                    "Chave de código temporário inválida."
            );
        }
        this.key = keyMaterial.clone();
        this.random = Objects.requireNonNull(random);
    }

    public String generateCode() {
        return "%08d".formatted(random.nextInt(CODE_SPACE));
    }

    public String digest(String challengeId, String code) {
        String normalizedCode = hasCodeShape(code) ? code : "00000000";
        String payload = DOMAIN + ":" + challengeId + ":" + normalizedCode;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Falha ao proteger código temporário.",
                    exception
            );
        }
    }

    public boolean matches(
            String challengeId,
            String code,
            String expectedDigest
    ) {
        String candidate = digest(challengeId, code);
        boolean digestShape = expectedDigest != null
                && expectedDigest.matches("[0-9a-f]{64}");
        byte[] expected = digestShape
                ? HexFormat.of().parseHex(expectedDigest)
                : new byte[32];
        byte[] actual = HexFormat.of().parseHex(candidate);
        return hasCodeShape(code)
                && digestShape
                && MessageDigest.isEqual(actual, expected);
    }

    private boolean hasCodeShape(String code) {
        return code != null && code.matches("[0-9]{8}");
    }
}

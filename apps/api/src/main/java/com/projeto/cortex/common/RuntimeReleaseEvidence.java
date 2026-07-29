package com.projeto.cortex.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/** Public, non-secret proof that the runtime database received a release migration. */
public record RuntimeReleaseEvidence(
        String databaseReleaseRevision,
        String databaseReleaseMarker
) {

    private static final Pattern FULL_GIT_REVISION = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern BASE64URL_WITHOUT_PADDING =
            Pattern.compile("[A-Za-z0-9_-]{43}");

    public RuntimeReleaseEvidence {
        if (!isFullGitRevision(databaseReleaseRevision)) {
            throw new IllegalArgumentException(
                    "A revisão pública do banco deve ser um SHA completo."
            );
        }
        if (!isCanonicalMarker(databaseReleaseMarker)) {
            throw new IllegalArgumentException(
                    "O marcador público do banco deve ser canônico."
            );
        }
        if (!expectedMarker(databaseReleaseRevision).equals(databaseReleaseMarker)) {
            throw new IllegalArgumentException(
                    "O marcador público do banco não corresponde à revisão."
            );
        }
    }

    public Map<String, String> asPublicEvidence() {
        return Map.of(
                "databaseReleaseRevision", databaseReleaseRevision,
                "databaseReleaseMarker", databaseReleaseMarker
        );
    }

    private static boolean isFullGitRevision(String value) {
        return value != null && FULL_GIT_REVISION.matcher(value).matches();
    }

    private static boolean isCanonicalMarker(String value) {
        if (value == null || !BASE64URL_WITHOUT_PADDING.matcher(value).matches()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 32
                    && Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(decoded)
                    .equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String expectedMarker(String revision) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    ("cortex-release-v1:" + revision)
                            .getBytes(StandardCharsets.UTF_8)
            );
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}

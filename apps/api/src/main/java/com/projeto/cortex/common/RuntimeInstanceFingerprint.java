package com.projeto.cortex.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Public, non-secret fingerprint of the Render process instance. */
@Component
public final class RuntimeInstanceFingerprint {

    private static final String LOCAL_INSTANCE = "local";

    private final String value;

    public RuntimeInstanceFingerprint(
            @Value("${RENDER_INSTANCE_ID:local}") String instanceId
    ) {
        String normalized = instanceId == null ? "" : instanceId.trim();
        String effectiveId = normalized.isEmpty() ? LOCAL_INSTANCE : normalized;
        this.value = sha256Base64Url(effectiveId);
    }

    public String value() {
        return value;
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}

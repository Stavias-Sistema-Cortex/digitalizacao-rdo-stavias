package com.projeto.cortex.auth.webauthn;

import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.otp.AuthRateLimitStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Shared opaque circuit breaker for public WebAuthn ceremonies. */
@Service
public class WebAuthnRateLimiter {

    private static final String BUCKET_DOMAIN =
            "cortex.auth.webauthn-rate-limit.v1";

    private final AuthRateLimitStore buckets;
    private final WebAuthnRateLimitPolicy policy;

    public WebAuthnRateLimiter(
            AuthRateLimitStore buckets,
            WebAuthnRateLimitPolicy policy
    ) {
        this.buckets = buckets;
        this.policy = policy;
    }

    public boolean allow(
            WebAuthnRateLimitAction action,
            String clientIp
    ) {
        if (action == null) {
            return false;
        }
        String scope = action.name().toLowerCase(Locale.ROOT);
        String globalBucket = bucketKey("global", scope);
        if (!buckets.hasCapacity(
                globalBucket,
                policy.globalMaxRequests(),
                policy.windowSeconds()
        )) {
            return false;
        }

        String source = clientIp == null || clientIp.isBlank()
                ? "invalid"
                : clientIp.strip();
        String ipBucket = bucketKey("source", scope + "\0" + source);
        if (!buckets.consume(
                List.of(ipBucket),
                policy.maxRequests(),
                policy.windowSeconds()
        )) {
            return false;
        }
        return buckets.consume(
                List.of(globalBucket),
                policy.globalMaxRequests(),
                policy.windowSeconds()
        );
    }

    public boolean allowIdentifier(AuthChallengeLookupMaterial material) {
        if (material == null) {
            return false;
        }
        List<String> identifierBuckets = material.candidates().stream()
                .map(candidate -> bucketKey(
                        "identifier",
                        candidate.keyId() + "\0" + candidate.value()
                ))
                .toList();
        return buckets.consume(
                identifierBuckets,
                policy.maxRequests(),
                policy.windowSeconds()
        );
    }

    private String bucketKey(String scope, String material) {
        return sha256(BUCKET_DOMAIN + "\0" + scope + "\0" + material);
    }

    private String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}

package com.projeto.cortex.auth.webauthn;

import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.otp.OtpCryptography;
import com.projeto.cortex.auth.otp.AuthRateLimitStore;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Shared, HMAC-keyed circuit breaker for public WebAuthn ceremonies. */
@Service
public class WebAuthnRateLimiter {

    private final AuthRateLimitStore buckets;
    private final OtpCryptography cryptography;
    private final WebAuthnRateLimitPolicy policy;

    public WebAuthnRateLimiter(
            AuthRateLimitStore buckets,
            OtpCryptography cryptography,
            WebAuthnRateLimitPolicy policy
    ) {
        this.buckets = buckets;
        this.cryptography = cryptography;
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
        String globalBucket = cryptography.bucketDigest(
                "global",
                "webauthn:" + scope
        );
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
        String ipBucket = cryptography.bucketDigest(
                "ip",
                "webauthn:" + scope + ":" + source
        );
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
                .map(candidate -> cryptography.bucketDigest(
                        "identifier",
                        "webauthn:authentication-options:"
                                + candidate.keyId()
                                + ":"
                                + candidate.value()
                ))
                .toList();
        return buckets.consume(
                identifierBuckets,
                policy.maxRequests(),
                policy.windowSeconds()
        );
    }
}

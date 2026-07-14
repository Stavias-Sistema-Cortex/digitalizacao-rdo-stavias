package com.projeto.cortex.auth.otp;

import java.util.List;
import org.springframework.stereotype.Service;

/** Shared local pre-gate plus global circuit breaker; raw inputs stay in HMAC. */
@Service
public class AuthRateLimiter {

    private final RateLimitBucketRepository buckets;
    private final OtpCryptography cryptography;
    private final OtpPolicy policy;

    public AuthRateLimiter(
            RateLimitBucketRepository buckets,
            OtpCryptography cryptography,
            OtpPolicy policy
    ) {
        this.buckets = buckets;
        this.cryptography = cryptography;
        this.policy = policy;
    }

    public boolean allow(String identifier, String clientIp) {
        String globalBucket = cryptography.bucketDigest(
                "global",
                "email-challenge"
        );
        if (!buckets.hasCapacity(
                globalBucket,
                policy.globalRateLimitMaxRequests(),
                policy.rateLimitWindowSeconds()
        )) {
            return false;
        }

        String ipBucket = cryptography.bucketDigest(
                "ip",
                AuthRequestNormalizer.clientIp(clientIp)
        );
        boolean sourceAllowed = buckets.consume(
                List.of(ipBucket),
                policy.rateLimitMaxRequests(),
                policy.rateLimitWindowSeconds()
        );
        if (!sourceAllowed) {
            return false;
        }

        boolean globallyAllowed = buckets.consume(
                List.of(globalBucket),
                policy.globalRateLimitMaxRequests(),
                policy.rateLimitWindowSeconds()
        );
        if (!globallyAllowed) {
            return false;
        }

        String identifierBucket = cryptography.bucketDigest(
                "identifier",
                AuthRequestNormalizer.identifier(identifier)
        );
        return buckets.consume(
                List.of(identifierBucket),
                policy.rateLimitMaxRequests(),
                policy.rateLimitWindowSeconds()
        );
    }

}

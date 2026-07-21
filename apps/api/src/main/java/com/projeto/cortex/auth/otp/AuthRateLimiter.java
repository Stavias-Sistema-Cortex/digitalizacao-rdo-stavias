package com.projeto.cortex.auth.otp;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Shared e-mail challenge pre-gate plus global circuit breaker. */
@Service
public class AuthRateLimiter {

    private final AuthRateLimitStore buckets;
    private final OtpCryptography cryptography;
    private final OtpPolicy policy;
    private final AuthenticationIdentifierNormalizer identifierNormalizer;

    /** Compatibility constructor for legacy tests and direct MySQL callers. */
    public AuthRateLimiter(
            AuthRateLimitStore buckets,
            OtpCryptography cryptography,
            OtpPolicy policy
    ) {
        this(buckets, cryptography, policy, new MysqlCpfIdentifierNormalizer());
    }

    @Autowired
    public AuthRateLimiter(
            AuthRateLimitStore buckets,
            OtpCryptography cryptography,
            OtpPolicy policy,
            AuthenticationIdentifierNormalizer identifierNormalizer
    ) {
        this.buckets = buckets;
        this.cryptography = cryptography;
        this.policy = policy;
        this.identifierNormalizer = identifierNormalizer;
    }

    public boolean allow(String identifier, String clientIp) {
        return allowScoped(
                "email-challenge",
                identifierNormalizer.canonicalize(identifier),
                clientIp
        );
    }

    private boolean allowScoped(
            String scope,
            String identifier,
            String clientIp
    ) {
        String globalBucket = cryptography.bucketDigest(
                "global",
                scope
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
                scope + ":" + AuthRequestNormalizer.clientIp(clientIp)
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
                scope + ":" + identifier
        );
        return buckets.consume(
                List.of(identifierBucket),
                policy.rateLimitMaxRequests(),
                policy.rateLimitWindowSeconds()
        );
    }

}

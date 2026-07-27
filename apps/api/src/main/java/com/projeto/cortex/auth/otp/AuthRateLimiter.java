package com.projeto.cortex.auth.otp;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

/** Shared e-mail challenge pre-gate plus global circuit breaker. */
@Service
@Profile("!postgresql | postgresql-activation")
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

    /**
     * Applies independent source and global throttles before an OTP challenge
     * can be locked and its code evaluated.
     */
    public boolean allowVerification(String challengeId, String clientIp) {
        return allowScoped(
                "email-verify",
                canonicalChallengeId(challengeId),
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

    private String canonicalChallengeId(String challengeId) {
        if (challengeId == null || challengeId.length() != 36) {
            return AuthenticationIdentifierNormalizer.INVALID_VALUE;
        }
        try {
            return UUID.fromString(challengeId).toString().equals(challengeId)
                    ? challengeId
                    : AuthenticationIdentifierNormalizer.INVALID_VALUE;
        } catch (IllegalArgumentException exception) {
            return AuthenticationIdentifierNormalizer.INVALID_VALUE;
        }
    }

}

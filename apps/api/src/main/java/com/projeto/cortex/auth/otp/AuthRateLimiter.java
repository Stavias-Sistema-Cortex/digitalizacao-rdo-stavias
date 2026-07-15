package com.projeto.cortex.auth.otp;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Shared source/identifier pre-gate plus global circuit breaker. */
@Service
public class AuthRateLimiter {

    private final RateLimitBucketRepository buckets;
    private final OtpCryptography cryptography;
    private final OtpPolicy policy;
    private final boolean enabled;

    public AuthRateLimiter(
            RateLimitBucketRepository buckets,
            OtpCryptography cryptography,
            OtpPolicy policy,
            @Value("${cortex.auth.otp.rate-limit-enabled:true}")
            boolean enabled
    ) {
        this.buckets = buckets;
        this.cryptography = cryptography;
        this.policy = policy;
        this.enabled = enabled;
    }

    public boolean allow(String identifier, String clientIp) {
        return allowScoped("email-challenge", identifier, clientIp);
    }

    public boolean allowCpfLogin(String identifier, String clientIp) {
        return allowScoped("cpf-login", identifier, clientIp);
    }

    private boolean allowScoped(
            String scope,
            String identifier,
            String clientIp
    ) {
        if (!enabled) {
            return true;
        }
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
                scope + ":" + AuthRequestNormalizer.identifier(identifier)
        );
        return buckets.consume(
                List.of(identifierBucket),
                policy.rateLimitMaxRequests(),
                policy.rateLimitWindowSeconds()
        );
    }

}

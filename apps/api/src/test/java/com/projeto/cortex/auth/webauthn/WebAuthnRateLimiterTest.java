package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.otp.OtpCryptography;
import com.projeto.cortex.auth.otp.RateLimitBucketRepository;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class WebAuthnRateLimiterTest {

    @Test
    void consumesHmacKeyedSourceAndGlobalBuckets() {
        RateLimitBucketRepository buckets = mock(
                RateLimitBucketRepository.class
        );
        when(buckets.hasCapacity(anyString(), eq(2_000), eq(900)))
                .thenReturn(true);
        when(buckets.consume(anyList(), eq(20), eq(900))).thenReturn(true);
        when(buckets.consume(anyList(), eq(2_000), eq(900)))
                .thenReturn(true);
        WebAuthnRateLimiter limiter = limiter(buckets);

        assertThat(limiter.allow(
                WebAuthnRateLimitAction.AUTHENTICATION_OPTIONS,
                "203.0.113.9"
        )).isTrue();

        verify(buckets).consume(anyList(), eq(20), eq(900));
        verify(buckets).consume(anyList(), eq(2_000), eq(900));
    }

    @Test
    void globalCircuitBreakerRejectsBeforeCreatingSourceBucket() {
        RateLimitBucketRepository buckets = mock(
                RateLimitBucketRepository.class
        );
        when(buckets.hasCapacity(anyString(), eq(2_000), eq(900)))
                .thenReturn(false);
        WebAuthnRateLimiter limiter = limiter(buckets);

        assertThat(limiter.allow(
                WebAuthnRateLimitAction.AUTHENTICATION_VERIFY,
                "203.0.113.9"
        )).isFalse();

        verify(buckets, never()).consume(anyList(), eq(20), eq(900));
        verify(buckets, never()).consume(anyList(), eq(2_000), eq(900));
    }

    private WebAuthnRateLimiter limiter(
            RateLimitBucketRepository buckets
    ) {
        OtpCryptography cryptography = new OtpCryptography(
                "test-only-passkey-rate-limit-key-0001".getBytes(),
                new SecureRandom()
        );
        return new WebAuthnRateLimiter(
                buckets,
                cryptography,
                new WebAuthnRateLimitPolicy(20, 2_000, 900)
        );
    }
}

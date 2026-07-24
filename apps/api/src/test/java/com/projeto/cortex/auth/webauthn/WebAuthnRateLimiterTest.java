package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.otp.OtpCryptography;
import com.projeto.cortex.auth.otp.RateLimitBucketRepository;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    @Test
    void consumesDistinctHmacBucketsForProtectedIdentifierMaterials() {
        RateLimitBucketRepository buckets = mock(
                RateLimitBucketRepository.class
        );
        when(buckets.consume(anyList(), eq(20), eq(900))).thenReturn(true);
        WebAuthnRateLimiter limiter = limiter(buckets);
        AuthChallengeLookupMaterial canonical =
                new AuthChallengeLookupMaterial(
                        List.of(new CpfLookupDigest(
                                "synthetic-current",
                                "a".repeat(64)
                        )),
                        "b".repeat(64)
                );
        AuthChallengeLookupMaterial decoy =
                new AuthChallengeLookupMaterial(
                        List.of(new CpfLookupDigest(
                                "synthetic-current",
                                "c".repeat(64)
                        )),
                        "d".repeat(64)
                );

        assertThat(limiter.allowIdentifier(canonical)).isTrue();
        assertThat(limiter.allowIdentifier(decoy)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(
                List.class
        );
        verify(buckets, org.mockito.Mockito.times(2)).consume(
                keys.capture(),
                eq(20),
                eq(900)
        );
        assertThat(keys.getAllValues())
                .allSatisfy(captured -> assertThat(captured)
                        .isNotEmpty()
                        .allMatch(key -> key.matches("[0-9a-f]{64}"))
                        .noneMatch(key -> key.contains("52998224725"))
                        .noneMatch(key -> key.contains("invalid-id!")));
        assertThat(keys.getAllValues().get(0))
                .doesNotContainAnyElementsOf(keys.getAllValues().get(1));
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

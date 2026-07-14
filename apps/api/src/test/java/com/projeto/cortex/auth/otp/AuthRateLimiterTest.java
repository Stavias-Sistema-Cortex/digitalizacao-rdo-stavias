package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthRateLimiterTest {

    @Test
    void hashesIdentifierAndIpIntoTwoDomainSeparatedBuckets() {
        RateLimitBucketRepository buckets = mock(
                RateLimitBucketRepository.class
        );
        OtpPolicy policy = new OtpPolicy(600, 5, 5, 1000, 900);
        OtpCryptography cryptography = new OtpCryptography(
                "test-only-otp-hmac-key-material-00000001".getBytes(),
                new SecureRandom()
        );
        when(buckets.consume(anyList(), eq(5), eq(900))).thenReturn(true);
        when(buckets.consume(anyList(), eq(1000), eq(900)))
                .thenReturn(true);
        when(buckets.hasCapacity(anyString(), eq(1000), eq(900)))
                .thenReturn(true);
        AuthRateLimiter limiter = new AuthRateLimiter(
                buckets,
                cryptography,
                policy
        );

        assertThat(limiter.allow("11144477735", "203.0.113.7")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(
                List.class
        );
        verify(buckets, times(2)).consume(keys.capture(), eq(5), eq(900));
        verify(buckets).consume(keys.capture(), eq(1000), eq(900));
        assertThat(keys.getAllValues()).hasSize(3)
                .allSatisfy(captured -> assertThat(captured)
                        .hasSize(1)
                        .allMatch(key -> key.matches("[0-9a-f]{64}"))
                        .noneMatch(key -> key.contains("11144477735"))
                        .noneMatch(key -> key.contains("203.0.113.7")));
        assertThat(keys.getAllValues().stream()
                .flatMap(List::stream)
                .toList()).hasSize(3)
                .doesNotHaveDuplicates()
                .allMatch(key -> key.matches("[0-9a-f]{64}"))
                .noneMatch(key -> key.contains("11144477735"))
                .noneMatch(key -> key.contains("203.0.113.7"));
        verify(buckets).hasCapacity(
                anyString(), eq(1000), eq(900)
        );
    }

    @Test
    void equivalentCpfAndIpRepresentationsShareTheSameBuckets() {
        RateLimitBucketRepository buckets = mock(
                RateLimitBucketRepository.class
        );
        OtpPolicy policy = new OtpPolicy(600, 5, 5, 1000, 900);
        OtpCryptography cryptography = new OtpCryptography(
                "test-only-otp-hmac-key-material-00000001".getBytes(),
                new SecureRandom()
        );
        when(buckets.consume(anyList(), eq(5), eq(900))).thenReturn(true);
        when(buckets.consume(anyList(), eq(1000), eq(900)))
                .thenReturn(true);
        when(buckets.hasCapacity(anyString(), eq(1000), eq(900)))
                .thenReturn(true);
        AuthRateLimiter limiter = new AuthRateLimiter(
                buckets,
                cryptography,
                policy
        );

        assertThat(limiter.allow(
                "111.444.777-35",
                "203.000.113.007"
        )).isTrue();
        assertThat(limiter.allow(
                "11144477735",
                "203.0.113.7"
        )).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(
                List.class
        );
        verify(buckets, times(4)).consume(
                keys.capture(), eq(5), eq(900)
        );
        verify(buckets, times(2)).consume(
                keys.capture(), eq(1000), eq(900)
        );
        List<List<String>> localBuckets = keys.getAllValues().subList(0, 4);
        assertThat(localBuckets.get(0))
                .containsExactlyElementsOf(localBuckets.get(2));
        assertThat(localBuckets.get(1))
                .containsExactlyElementsOf(localBuckets.get(3));
        verify(buckets, times(2)).hasCapacity(
                anyString(), eq(1000), eq(900)
        );
    }

    @Test
    void blockedLocalBucketsDoNotSpendTheGlobalBudget() {
        RateLimitBucketRepository buckets = mock(
                RateLimitBucketRepository.class
        );
        OtpPolicy policy = new OtpPolicy(600, 5, 5, 100, 900);
        OtpCryptography cryptography = new OtpCryptography(
                "test-only-otp-hmac-key-material-00000001".getBytes(),
                new SecureRandom()
        );
        when(buckets.consume(anyList(), eq(5), eq(900)))
                .thenReturn(false);
        when(buckets.hasCapacity(anyString(), eq(100), eq(900)))
                .thenReturn(true);
        AuthRateLimiter limiter = new AuthRateLimiter(
                buckets,
                cryptography,
                policy
        );

        assertThat(limiter.allow(
                "11144477735",
                "203.0.113.7"
        )).isFalse();

        verify(buckets).consume(anyList(), eq(5), eq(900));
        verify(buckets, never()).consume(anyList(), eq(100), eq(900));
    }

    @Test
    void exhaustedGlobalBudgetStopsBeforeCreatingLocalBuckets() {
        RateLimitBucketRepository buckets = mock(
                RateLimitBucketRepository.class
        );
        OtpPolicy policy = new OtpPolicy(600, 5, 5, 100, 900);
        OtpCryptography cryptography = new OtpCryptography(
                "test-only-otp-hmac-key-material-00000001".getBytes(),
                new SecureRandom()
        );
        when(buckets.hasCapacity(anyString(), eq(100), eq(900)))
                .thenReturn(false);
        AuthRateLimiter limiter = new AuthRateLimiter(
                buckets,
                cryptography,
                policy
        );

        assertThat(limiter.allow(
                "11144477735",
                "203.0.113.7"
        )).isFalse();

        verify(buckets).hasCapacity(anyString(), eq(100), eq(900));
        verify(buckets, never()).consume(anyList(), eq(5), eq(900));
        verify(buckets, never()).consume(anyList(), eq(100), eq(900));
    }
}

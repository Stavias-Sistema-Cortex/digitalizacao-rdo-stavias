package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.CpfLookupDigestService;
import com.projeto.cortex.auth.otp.AuthRateLimitStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuthLoginRateLimiterTest {

    @Test
    void consumesPersistentSourceGlobalAndProtectedCpfBuckets() {
        AuthRateLimitStore buckets = mock(AuthRateLimitStore.class);
        CpfLookupDigestService digests = mock(CpfLookupDigestService.class);
        when(digests.challengeLookup("11144477735")).thenReturn(material());
        when(buckets.hasCapacity(anyString(), eq(10), eq(60))).thenReturn(true);
        when(buckets.consume(anyList(), anyInt(), eq(60))).thenReturn(true);
        AuthLoginRateLimiter limiter = new AuthLoginRateLimiter(
                buckets, digests, 2, 10, 60
        );

        limiter.check("11144477735", "198.51.100.25");

        verify(digests).challengeLookup("11144477735");
        verify(buckets, times(3)).consume(anyList(), anyInt(), eq(60));
    }

    @Test
    void rejectsWhenThePersistentGlobalBucketIsFull() {
        AuthRateLimitStore buckets = mock(AuthRateLimitStore.class);
        CpfLookupDigestService digests = mock(CpfLookupDigestService.class);
        when(digests.challengeLookup("11144477735")).thenReturn(material());
        when(buckets.hasCapacity(anyString(), eq(10), eq(60))).thenReturn(false);
        AuthLoginRateLimiter limiter = new AuthLoginRateLimiter(
                buckets, digests, 2, 10, 60
        );

        assertThatThrownBy(() -> limiter.check("11144477735", "198.51.100.25"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Limite de tentativas");
        verify(digests).challengeLookup("11144477735");
    }

    @Test
    void sharesSourceAndGlobalBucketsAcrossCpfValuesButKeepsIdentifierBucketsSeparate() {
        RecordingRateLimitStore buckets = new RecordingRateLimitStore();
        CpfLookupDigestService digests = mock(CpfLookupDigestService.class);
        when(digests.challengeLookup("11144477735"))
                .thenReturn(material("a", "b"));
        when(digests.challengeLookup("52998224725"))
                .thenReturn(material("c", "d"));
        AuthLoginRateLimiter limiter = new AuthLoginRateLimiter(
                buckets, digests, 5, 10, 60
        );

        limiter.check("11144477735", "198.51.100.25");
        limiter.check("52998224725", "198.51.100.25");

        assertThat(buckets.capacityKeys).containsExactly(
                buckets.consumedKeys.get(1),
                buckets.consumedKeys.get(4)
        );
        assertThat(buckets.consumedKeys.get(0))
                .isEqualTo(buckets.consumedKeys.get(3));
        assertThat(buckets.consumedKeys.get(1))
                .isEqualTo(buckets.consumedKeys.get(4));
        assertThat(buckets.consumedKeys.get(2))
                .isNotEqualTo(buckets.consumedKeys.get(5));
        assertThat(buckets.consumedKeys)
                .allMatch(key -> key.matches("[0-9a-f]{64}"));
    }

    private AuthChallengeLookupMaterial material() {
        return material("a", "b");
    }

    private AuthChallengeLookupMaterial material(
            String current,
            String legacy
    ) {
        return new AuthChallengeLookupMaterial(
                List.of(new CpfLookupDigest("current", current.repeat(64))),
                legacy.repeat(64)
        );
    }

    private static final class RecordingRateLimitStore
            implements AuthRateLimitStore {

        private final List<String> capacityKeys = new ArrayList<>();
        private final List<String> consumedKeys = new ArrayList<>();

        @Override
        public boolean hasCapacity(
                String bucketKey,
                int maxRequests,
                int windowSeconds
        ) {
            capacityKeys.add(bucketKey);
            return true;
        }

        @Override
        public boolean consume(
                List<String> bucketKeys,
                int maxRequests,
                int windowSeconds
        ) {
            consumedKeys.addAll(bucketKeys);
            return true;
        }
    }
}

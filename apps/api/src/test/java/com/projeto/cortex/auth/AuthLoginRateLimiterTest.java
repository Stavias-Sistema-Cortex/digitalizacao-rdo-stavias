package com.projeto.cortex.auth;

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

    private AuthChallengeLookupMaterial material() {
        return new AuthChallengeLookupMaterial(
                List.of(new CpfLookupDigest("current", "a".repeat(64))),
                "b".repeat(64)
        );
    }
}

package com.projeto.cortex.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthLoginRateLimiterTest {

    @Test
    void blocksRepeatedPublicLoginAttemptsFromTheSameSource() {
        AuthLoginRateLimiter limiter = new AuthLoginRateLimiter(
                true,
                2,
                Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC)
        );

        limiter.check("198.51.100.25");
        limiter.check("198.51.100.25");

        assertThatThrownBy(() -> limiter.check("198.51.100.25"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Limite de tentativas");
    }
}

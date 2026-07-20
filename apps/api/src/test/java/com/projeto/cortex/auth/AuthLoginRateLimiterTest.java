package com.projeto.cortex.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthLoginRateLimiterTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RateLimiterConfiguration.class)
                    .withPropertyValues(
                            "cortex.auth.login-rate-limit.enabled=true",
                            "cortex.auth.login-rate-limit.attempts-per-minute=2",
                            "cortex.auth.login-rate-limit.global-attempts-per-minute=10"
                    );

    @Test
    void startsWithTheConfiguredConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuthLoginRateLimiter.class);
        });
    }

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

    @Configuration(proxyBeanMethods = false)
    @Import(AuthLoginRateLimiter.class)
    static class RateLimiterConfiguration {
    }
}

package com.projeto.cortex.financeiro.cobranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FinanceChargeRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-14T15:00:00Z");

    @Test
    void appliesBoundedExponentialBackoffOnlyToKnownSafeFailures() {
        FinanceChargeRetryPolicy policy = new FinanceChargeRetryPolicy(
                4,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10)
        );

        assertThat(policy.nextAttemptAt(1, NOW, true))
                .contains(NOW.plus(Duration.ofMinutes(2)));
        assertThat(policy.nextAttemptAt(2, NOW, true))
                .contains(NOW.plus(Duration.ofMinutes(4)));
        assertThat(policy.nextAttemptAt(3, NOW, true))
                .contains(NOW.plus(Duration.ofMinutes(8)));
        assertThat(policy.nextAttemptAt(4, NOW, true)).isEmpty();
    }

    @Test
    void neverAutomaticallyRetriesAnAmbiguousProviderOutcome() {
        FinanceChargeRetryPolicy policy = new FinanceChargeRetryPolicy(
                4,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10)
        );

        assertThat(policy.nextAttemptAt(1, NOW, false)).isEmpty();
    }
}

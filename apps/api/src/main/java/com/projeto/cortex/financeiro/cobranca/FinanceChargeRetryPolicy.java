package com.projeto.cortex.financeiro.cobranca;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("legacy-finance")
public final class FinanceChargeRetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;

    @Autowired
    public FinanceChargeRetryPolicy(
            @Value("${cortex.finance.email.retry.max-attempts:4}")
            int maxAttempts,
            @Value("${cortex.finance.email.retry.initial-delay-seconds:120}")
            long initialDelaySeconds,
            @Value("${cortex.finance.email.retry.max-delay-seconds:3600}")
            long maxDelaySeconds
    ) {
        this(
                maxAttempts,
                Duration.ofSeconds(initialDelaySeconds),
                Duration.ofSeconds(maxDelaySeconds)
        );
    }

    FinanceChargeRetryPolicy(
            int maxAttempts,
            Duration initialDelay,
            Duration maxDelay
    ) {
        if (maxAttempts < 1
                || maxAttempts > 20
                || initialDelay == null
                || initialDelay.isNegative()
                || initialDelay.isZero()
                || maxDelay == null
                || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "Política de retentativa de cobrança inválida."
            );
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
    }

    public Optional<Instant> nextAttemptAt(
            int completedAttempts,
            Instant now,
            boolean knownSafeToRetry
    ) {
        if (!knownSafeToRetry || completedAttempts >= maxAttempts) {
            return Optional.empty();
        }
        if (completedAttempts < 1 || now == null) {
            throw new IllegalArgumentException(
                    "Tentativa de cobrança inválida."
            );
        }
        long factor = 1L << Math.min(completedAttempts - 1, 30);
        Duration delay;
        try {
            delay = initialDelay.multipliedBy(factor);
        } catch (ArithmeticException exception) {
            delay = maxDelay;
        }
        if (delay.compareTo(maxDelay) > 0) {
            delay = maxDelay;
        }
        return Optional.of(now.plus(delay));
    }
}

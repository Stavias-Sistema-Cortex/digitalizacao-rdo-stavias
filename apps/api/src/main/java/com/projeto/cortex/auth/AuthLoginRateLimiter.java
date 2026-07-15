package com.projeto.cortex.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Limite simples para a única rota pública de CPF. O perfil local o desliga
 * explicitamente para não bloquear o desenvolvimento; em produção há limites
 * por origem e globais, ambos com janela fixa curta.
 */
@Service
public class AuthLoginRateLimiter {

    private static final long WINDOW_SECONDS = 60;
    private static final int PRUNE_THRESHOLD = 10_000;

    private final boolean enabled;
    private final int attemptsPerMinute;
    private final int globalAttemptsPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows =
            new ConcurrentHashMap<>();

    public AuthLoginRateLimiter(
            @Value("${cortex.auth.login-rate-limit.enabled:true}")
            boolean enabled,
            @Value("${cortex.auth.login-rate-limit.attempts-per-minute:20}")
            int attemptsPerMinute,
            @Value("${cortex.auth.login-rate-limit.global-attempts-per-minute:1000}")
            int globalAttemptsPerMinute
    ) {
        this(
                enabled,
                attemptsPerMinute,
                globalAttemptsPerMinute,
                Clock.systemUTC()
        );
    }

    AuthLoginRateLimiter(
            boolean enabled,
            int attemptsPerMinute,
            Clock clock
    ) {
        this(enabled, attemptsPerMinute, Math.max(100, attemptsPerMinute), clock);
    }

    AuthLoginRateLimiter(
            boolean enabled,
            int attemptsPerMinute,
            int globalAttemptsPerMinute,
            Clock clock
    ) {
        this.enabled = enabled;
        this.attemptsPerMinute = Math.max(1, attemptsPerMinute);
        this.globalAttemptsPerMinute = Math.max(1, globalAttemptsPerMinute);
        this.clock = clock;
    }

    public void check(String clientAddress) {
        if (!enabled) {
            return;
        }

        Instant now = clock.instant();
        String source = clientAddress == null || clientAddress.isBlank()
                ? "unknown"
                : clientAddress.trim();

        if (!consume("source:" + source, attemptsPerMinute, now)
                || !consume("global", globalAttemptsPerMinute, now)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Limite de tentativas atingido. Tente novamente em instantes."
            );
        }

        if (windows.size() > PRUNE_THRESHOLD) {
            Instant earliestActive = now.minusSeconds(WINDOW_SECONDS);
            windows.entrySet().removeIf(entry ->
                    entry.getValue().startedAt().isBefore(earliestActive)
            );
        }
    }

    private boolean consume(String key, int maxAttempts, Instant now) {
        AtomicBoolean allowed = new AtomicBoolean(false);
        windows.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(
                    current.startedAt().plusSeconds(WINDOW_SECONDS)
            )) {
                allowed.set(true);
                return new Window(now, 1);
            }
            if (current.attempts() >= maxAttempts) {
                return current;
            }
            allowed.set(true);
            return new Window(current.startedAt(), current.attempts() + 1);
        });
        return allowed.get();
    }

    private record Window(Instant startedAt, int attempts) {
    }
}

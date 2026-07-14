package com.projeto.cortex.auth.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded lifetime for revocable server-side sessions. */
@Component
public final class AuthSessionProperties {

    private static final int MINIMUM_TTL_SECONDS = 300;
    private static final int MAXIMUM_TTL_SECONDS = 2_592_000;

    private final int ttlSeconds;

    public AuthSessionProperties(
            @Value("${cortex.auth.session.ttl-seconds:43200}")
            int ttlSeconds
    ) {
        if (ttlSeconds < MINIMUM_TTL_SECONDS
                || ttlSeconds > MAXIMUM_TTL_SECONDS) {
            throw new IllegalStateException(
                    "Duração da sessão de autenticação inválida."
            );
        }
        this.ttlSeconds = ttlSeconds;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }
}

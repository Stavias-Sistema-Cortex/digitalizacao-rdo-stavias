package com.projeto.cortex.auth;

/**
 * Quanto o login direto por CPF aceita antes de fechar.
 *
 * <p>O limite por origem precisa comportar muitos aparelhos de uma obra atrás
 * do mesmo roteador. A combinação do teto global com o custo do Argon2id ainda
 * impede tentativas ilimitadas; a janela continua igual à da passkey.
 */
public record AuthLoginRateLimitPolicy(
        int maxRequests,
        int globalMaxRequests,
        int windowSeconds
) {

    public AuthLoginRateLimitPolicy {
        if (maxRequests < 1
                || maxRequests > 1_000
                || globalMaxRequests < maxRequests
                || globalMaxRequests > 100_000
                || windowSeconds < 60
                || windowSeconds > 3_600) {
            throw new IllegalStateException(
                    "Política de rate limit de login inválida."
            );
        }
    }
}

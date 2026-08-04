package com.projeto.cortex.auth;

/**
 * Quanto o login direto por CPF aceita antes de fechar.
 *
 * <p>Os limites da porta de passkey moram em {@code WebAuthnRateLimitPolicy} e
 * são deliberadamente mais frouxos: lá cada tentativa exige uma assinatura que
 * só o aparelho certo produz, então repetir não aproxima ninguém de entrar.
 * Aqui repetir é exatamente o ataque — o CPF é a credencial inteira, e cada
 * tentativa a mais é um palpite a mais. Por isso o teto por origem é menor e a
 * janela, a mesma.
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

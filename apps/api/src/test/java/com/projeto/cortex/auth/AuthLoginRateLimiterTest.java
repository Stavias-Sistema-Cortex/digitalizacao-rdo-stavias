package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.auth.otp.AuthRateLimitStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * O disjuntor da porta que o campo usa.
 *
 * <p>Estes testes não medem contagem — quem conta é o repositório de baldes, e
 * ele já tem os seus. O que se guarda aqui é o comportamento que só existe
 * nesta classe: que os dois baldes são independentes, que o global é olhado
 * antes de o balde da origem ser gasto, e que uma origem que não dá para
 * distinguir não vira chave em branco compartilhada com ninguém.
 */
class AuthLoginRateLimiterTest {

    /** Balde de mentira fiel ao contrato: conta, e devolve falso ao estourar. */
    private static final class BaldesDeMentira implements AuthRateLimitStore {

        private final Map<String, Integer> contagem = new HashMap<>();
        private final List<String> ordem = new ArrayList<>();

        @Override
        public boolean hasCapacity(
                String bucketKey,
                int maxRequests,
                int windowSeconds
        ) {
            ordem.add("hasCapacity:" + bucketKey);
            return contagem.getOrDefault(bucketKey, 0) < maxRequests;
        }

        @Override
        public boolean consume(
                List<String> bucketKeys,
                int maxRequests,
                int windowSeconds
        ) {
            boolean permitido = true;
            for (String key : bucketKeys) {
                ordem.add("consume:" + key);
                int proximo = contagem.merge(key, 1, Integer::sum);
                permitido &= proximo <= maxRequests;
            }
            return permitido;
        }
    }

    private final BaldesDeMentira baldes = new BaldesDeMentira();

    private AuthLoginRateLimiter limitador(int porOrigem, int global) {
        return new AuthLoginRateLimiter(
                baldes,
                new AuthLoginRateLimitPolicy(porOrigem, global, 900)
        );
    }

    @Test
    void deixaPassarAteOTetoDaOrigemERecusaDepois() {
        AuthLoginRateLimiter limitador = limitador(3, 100);

        assertThat(limitador.allow("203.0.113.10")).isTrue();
        assertThat(limitador.allow("203.0.113.10")).isTrue();
        assertThat(limitador.allow("203.0.113.10")).isTrue();
        assertThat(limitador.allow("203.0.113.10")).isFalse();
    }

    /**
     * Um endereço estourado não pode fechar a porta para os outros: é o que
     * separa um freio de uma negação de serviço feita pelo próprio freio.
     */
    @Test
    void naoDeixaUmaOrigemEstouradaBloquearOutra() {
        AuthLoginRateLimiter limitador = limitador(2, 100);

        assertThat(limitador.allow("203.0.113.10")).isTrue();
        assertThat(limitador.allow("203.0.113.10")).isTrue();
        assertThat(limitador.allow("203.0.113.10")).isFalse();

        assertThat(limitador.allow("198.51.100.7")).isTrue();
    }

    /**
     * O piso global é o que continua valendo quando o IP não distingue
     * ninguém — atrás de proxy sem CIDR confiável configurado, todo mundo
     * chega com o mesmo endereço, e sem este balde o freio seria decorativo.
     */
    @Test
    void oPisoGlobalSeguraAindaQueCadaTentativaVenhaDeUmaOrigemNova() {
        // Teto por origem igual ao piso global: cada endereço tem folga de
        // sobra, então quem recusa a quarta tentativa só pode ser o piso.
        AuthLoginRateLimiter limitador = limitador(3, 3);

        assertThat(limitador.allow("203.0.113.1")).isTrue();
        assertThat(limitador.allow("203.0.113.2")).isTrue();
        assertThat(limitador.allow("203.0.113.3")).isTrue();
        assertThat(limitador.allow("203.0.113.4")).isFalse();
    }

    /**
     * Consultar o global primeiro evita queimar o balde de quem chega depois:
     * com o piso estourado a recusa sai sem gastar nada por origem.
     */
    @Test
    void consultaOGlobalAntesDeGastarOBaldeDaOrigem() {
        AuthLoginRateLimiter limitador = limitador(1, 1);

        assertThat(limitador.allow("203.0.113.1")).isTrue();
        baldes.ordem.clear();

        assertThat(limitador.allow("203.0.113.9")).isFalse();
        assertThat(baldes.ordem).hasSize(1);
        assertThat(baldes.ordem.getFirst()).startsWith("hasCapacity:");
    }

    /** Endereço ausente é uma origem só, e não a mesma chave do balde global. */
    @Test
    void trataOrigemDesconhecidaComoUmaOrigemDistinta() {
        AuthLoginRateLimiter limitador = limitador(2, 100);

        assertThat(limitador.allow(null)).isTrue();
        assertThat(limitador.allow("   ")).isTrue();
        assertThat(limitador.allow(null)).isFalse();

        assertThat(limitador.allow("203.0.113.10")).isTrue();
    }

    /** As chaves entram no banco como char(64) hexadecimal minúsculo. */
    @Test
    void produzChavesNoFormatoQueOArmazenamentoAceita() {
        limitador(5, 100).allow("203.0.113.10");

        assertThat(baldes.contagem.keySet())
                .isNotEmpty()
                .allMatch(key -> key.matches("[0-9a-f]{64}"));
    }

    /** Política impossível é erro de configuração, não freio silenciosamente frouxo. */
    @Test
    void recusaPoliticaQueNaoLimitaNada() {
        assertThatThrownBy(() -> new AuthLoginRateLimitPolicy(100, 10, 900))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthLoginRateLimitPolicy(0, 100, 900))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthLoginRateLimitPolicy(5, 100, 10))
                .isInstanceOf(IllegalStateException.class);
    }
}

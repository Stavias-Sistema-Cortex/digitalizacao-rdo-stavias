package com.projeto.cortex.obras.mapa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O traçado manual do trecho, e o momento em que ele cede ao RDO.
 *
 * <p>Havia duas fontes para a mesma linha: o desenho feito à mão no mapa e o
 * quilômetro declarado no apontamento, projetado sobre o eixo. O desenho
 * vencia — era tratado como a posição de quem estava lá — e por isso um RDO
 * corrigido pelo quilômetro continuava mostrando a linha antiga, desenhada
 * antes da correção. O dono do sistema decidiu o contrário, e por inteiro:
 * <b>o RDO é a hierarquia</b>. Quando o apontamento declara quilômetro, é ele
 * que desenha; o traçado manual daquele RDO sai de vigência e a linha derivada
 * — cidade, quilômetro e pista vindos do próprio RDO — assume na leitura
 * seguinte, nos dois painéis.
 *
 * <p>Encerrar, não apagar: a linha desenhada continua no histórico da
 * geometria, como tudo que já esteve no mapa. E só cede quando o RDO tem o que
 * dizer — um apontamento sem quilômetro não derruba o desenho de ninguém,
 * porque não haveria linha nenhuma no lugar.
 */
@Component
public class DesenhoDoTrechoNoMapa {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DesenhoDoTrechoNoMapa.class);

    private final JdbcTemplate jdbcTemplate;

    public DesenhoDoTrechoNoMapa(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Tira de vigência os traçados manuais do RDO, para a derivada assumir.
     *
     * <p>Devolve quantos cederam. Zero é o caso comum — a maioria dos RDOs
     * nunca teve desenho próprio — e também o resultado de chamar de novo:
     * ceder é idempotente, porque só alcança o que ainda está vigente.
     */
    public int cederAoRdo(String rdoId) {
        int cederam = jdbcTemplate.update(
                """
                UPDATE obra_geometria
                SET valido_ate = CURRENT_TIMESTAMP(6),
                    status = 'ENCERRADA',
                    atualizado_em = CURRENT_TIMESTAMP(6)
                WHERE categoria = 'TRECHO'
                  AND objeto_tipo = 'RDO'
                  AND objeto_id = ?
                  AND valido_ate IS NULL
                  AND status = 'ATIVA'
                """,
                rdoId
        );
        if (cederam > 0) {
            LOGGER.info(
                    "O RDO {} declarou quilômetro e {} traçado(s) manual(is)"
                            + " cederam à linha derivada.",
                    rdoId,
                    cederam
            );
        }
        return cederam;
    }
}

package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * O quilômetro que o eixo da obra declara, e quem tem autoridade para mudá-lo.
 *
 * <p>O eixo é a régua: uma linha só, cadastrada uma vez, sobre a qual todo
 * apontamento de RDO é projetado para virar trecho no mapa. Os quilômetros
 * inicial e final moram nas propriedades dessa linha e dizem a que ponto da
 * rodovia cada extremidade corresponde.
 *
 * <p>Quem manda no número é o RDO. Foi assim que o dono do sistema decidiu, e a
 * consequência está registrada: a extensão da obra passa a ser a do último RDO
 * que declarou trecho interditado, e os trechos derivados dos demais RDOs se
 * movem junto, porque todos são projetados sobre a mesma régua. O eixo continua
 * sendo desenhado no mapa uma vez só; o que o RDO reescreve é o rótulo de
 * quilômetro das suas pontas, nunca a geometria.
 *
 * <p>RDO cancelado não reescreve nada. Apagar o RDO de teste do dia não pode
 * encolher a régua da obra para sempre — é a mesma regra que o PDOR segue para
 * não contar produção de RDO morto.
 */
@Component
public class QuilometroDoEixo {

    /** Propriedade do eixo com o quilômetro da primeira ponta. */
    public static final String KM_INICIAL = "kmInicial";

    /** Propriedade do eixo com o quilômetro da última ponta. */
    public static final String KM_FINAL = "kmFinal";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(QuilometroDoEixo.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public QuilometroDoEixo(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * O quilômetro que o eixo vigente declara, ou {@code null} se não há eixo.
     *
     * <p>É isto que o contexto de criação leva para o RDO novo já nascer com os
     * campos preenchidos: sem eixo cadastrado não há o que sugerir, e sugerir
     * zero seria pior do que deixar em branco.
     */
    public Faixa faixaVigente(String obraId) {
        List<Faixa> encontradas = jdbcTemplate.query(
                """
                SELECT propriedades_json
                FROM obra_geometria
                WHERE obra_id = ?
                  AND categoria = ?
                  AND status = 'ATIVA'
                  AND valido_ate IS NULL
                ORDER BY valido_desde DESC, id
                LIMIT 1
                """,
                (rs, rowNum) -> lerFaixa(rs.getString("propriedades_json")),
                obraId,
                TrechoApoiadoNoEixo.CATEGORIA_EIXO
        );
        return encontradas.isEmpty() ? null : encontradas.get(0);
    }

    /**
     * Reescreve o quilômetro do eixo com o que o RDO declarou.
     *
     * <p>Devolve {@code true} quando algo mudou de fato. Não havendo eixo, ou
     * vindo quilômetro incompleto, não faz nada: o RDO pode ser salvo sem
     * trecho interditado, e isso não é uma declaração de que a obra encolheu.
     *
     * <p>A gravação toca só as duas propriedades. A geometria, a vigência e a
     * autoria da linha ficam como estavam, porque o RDO está corrigindo o
     * rótulo de uma régua que já existe — não desenhando outra.
     *
     * <p>{@code atualizado_por} em particular fica intocado: ele é chave
     * estrangeira para colaborador e significa quem desenhou esta linha pelo
     * mapa. Um RDO não é um colaborador, e carimbar o identificador do RDO ali
     * não caberia na coluna nem existiria na tabela apontada. Quem mudou o
     * quilômetro está registrado no próprio RDO, que é onde a mudança foi
     * declarada, e no log desta operação.
     */
    public boolean reescrever(
            String obraId,
            BigDecimal kmInicial,
            BigDecimal kmFinal
    ) {
        if (kmInicial == null || kmFinal == null) {
            return false;
        }
        Faixa vigente = faixaVigente(obraId);
        if (vigente == null) {
            return false;
        }
        if (vigente.declarada()
                && vigente.kmInicial().compareTo(kmInicial) == 0
                && vigente.kmFinal().compareTo(kmFinal) == 0) {
            return false;
        }

        int alteradas = jdbcTemplate.update(
                """
                UPDATE obra_geometria
                SET propriedades_json =
                        jsonb_set(
                            jsonb_set(
                                propriedades_json::jsonb,
                                ?::text[],
                                to_jsonb(?::numeric),
                                true
                            ),
                            ?::text[],
                            to_jsonb(?::numeric),
                            true
                        )::json
                WHERE obra_id = ?
                  AND categoria = ?
                  AND status = 'ATIVA'
                  AND valido_ate IS NULL
                """,
                "{" + KM_INICIAL + "}",
                kmInicial,
                "{" + KM_FINAL + "}",
                kmFinal,
                obraId,
                TrechoApoiadoNoEixo.CATEGORIA_EIXO
        );
        if (alteradas > 0) {
            LOGGER.info(
                    "Eixo da obra {} passou a declarar km {} a {} por decisão do RDO.",
                    obraId,
                    kmInicial.toPlainString(),
                    kmFinal.toPlainString()
            );
        }
        return alteradas > 0;
    }

    private Faixa lerFaixa(String propriedadesJson) {
        if (propriedadesJson == null || propriedadesJson.isBlank()) {
            return Faixa.semDeclaracao();
        }
        try {
            JsonNode propriedades = objectMapper.readTree(propriedadesJson);
            if (!(propriedades instanceof ObjectNode)) {
                return Faixa.semDeclaracao();
            }
            BigDecimal inicial = decimal(propriedades.get(KM_INICIAL));
            BigDecimal fim = decimal(propriedades.get(KM_FINAL));
            if (inicial == null || fim == null) {
                return Faixa.semDeclaracao();
            }
            return new Faixa(inicial, fim);
        } catch (Exception exception) {
            /*
             * Propriedade ilegível é eixo sem quilômetro declarado, não erro do
             * cálculo que a consultou. Quem lê isto está montando um RDO ou
             * desenhando um mapa: nenhum dos dois deve morrer porque o rótulo
             * de uma linha está corrompido.
             */
            LOGGER.warn(
                    "Propriedades do eixo ilegíveis; seguindo sem quilômetro declarado.",
                    exception
            );
            return Faixa.semDeclaracao();
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim().replace(',', '.'));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * O par de quilômetros do eixo.
     *
     * <p>{@code declarada()} falso significa eixo desenhado sem quilômetro —
     * caso real, porque a linha pode ser cadastrada antes de alguém saber a
     * estaca. Nesse estado ele ainda serve de régua para nada, e é por isso que
     * o contexto de criação não sugere nada.
     */
    public record Faixa(BigDecimal kmInicial, BigDecimal kmFinal) {

        public static Faixa semDeclaracao() {
            return new Faixa(null, null);
        }

        public boolean declarada() {
            return kmInicial != null && kmFinal != null;
        }
    }
}

package com.projeto.cortex.intelligence.stavia.knowledge.team;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDate;
import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDateTime;
import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalTime;

@Component
public class JdbcTeamReader implements TeamReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTeamReader(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TeamRecord> findByWorksiteId(
            String worksiteId
    ) {
        if (
                worksiteId == null
                || worksiteId.isBlank()
        ) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT *
                FROM (
                    SELECT
                        mo.id AS labor_record_id,
                        r.obra_id,
                        r.id AS rdo_id,
                        r.numero_rdo,
                        r.data_rdo,
                        r.status AS rdo_status,
                        mo.colaborador_id,
                        mo.nome_colaborador,
                        c.nome AS collaborator_name,
                        mo.cargo,
                        mo.tipo_vinculo,
                        mo.quantidade,
                        mo.hora_inicio,
                        mo.hora_fim,
                        mo.atualizado_em
                    FROM rdo r
                    JOIN rdo_mao_obra mo
                      ON mo.rdo_id = r.id
                    LEFT JOIN colaborador c
                      ON c.id = mo.colaborador_id
                     AND c.deletado_em IS NULL
                    WHERE r.obra_id = ?
                      AND r.cancelado_em IS NULL

                    UNION ALL

                    SELECT
                        CONCAT(r.id, ':encarregado_obra') AS labor_record_id,
                        r.obra_id,
                        r.id AS rdo_id,
                        r.numero_rdo,
                        r.data_rdo,
                        r.status AS rdo_status,
                        NULL AS colaborador_id,
                        r.encarregado_obra AS nome_colaborador,
                        NULL AS collaborator_name,
                        'Encarregado da obra' AS cargo,
                        'RDO_CABECALHO' AS tipo_vinculo,
                        1 AS quantidade,
                        r.hora_inicio,
                        r.hora_fim,
                        r.atualizado_em
                    FROM rdo r
                    WHERE r.obra_id = ?
                      AND r.cancelado_em IS NULL
                      AND r.encarregado_obra IS NOT NULL
                      AND r.encarregado_obra <> ''

                    UNION ALL

                    SELECT
                        CONCAT(r.id, ':apontador_rdo') AS labor_record_id,
                        r.obra_id,
                        r.id AS rdo_id,
                        r.numero_rdo,
                        r.data_rdo,
                        r.status AS rdo_status,
                        NULL AS colaborador_id,
                        r.apontador_rdo AS nome_colaborador,
                        NULL AS collaborator_name,
                        'Apontador do RDO' AS cargo,
                        'RDO_CABECALHO' AS tipo_vinculo,
                        1 AS quantidade,
                        r.hora_inicio,
                        r.hora_fim,
                        r.atualizado_em
                    FROM rdo r
                    WHERE r.obra_id = ?
                      AND r.cancelado_em IS NULL
                      AND r.apontador_rdo IS NOT NULL
                      AND r.apontador_rdo <> ''

                    UNION ALL

                    SELECT
                        CONCAT(r.id, ':preenchido_por') AS labor_record_id,
                        r.obra_id,
                        r.id AS rdo_id,
                        r.numero_rdo,
                        r.data_rdo,
                        r.status AS rdo_status,
                        NULL AS colaborador_id,
                        r.preenchido_por AS nome_colaborador,
                        NULL AS collaborator_name,
                        'Preenchido por' AS cargo,
                        'RDO_CABECALHO' AS tipo_vinculo,
                        1 AS quantidade,
                        r.hora_inicio,
                        r.hora_fim,
                        r.atualizado_em
                    FROM rdo r
                    WHERE r.obra_id = ?
                      AND r.cancelado_em IS NULL
                      AND r.preenchido_por IS NOT NULL
                      AND r.preenchido_por <> ''

                    UNION ALL

                    SELECT
                        CONCAT(p.id, ':encarregado_programacao') AS labor_record_id,
                        p.obra_id,
                        NULL AS rdo_id,
                        CONCAT('Programação ', DATE_FORMAT(p.data_programacao, '%d/%m/%Y')) AS numero_rdo,
                        p.data_programacao AS data_rdo,
                        p.status AS rdo_status,
                        p.encarregado_colaborador_id AS colaborador_id,
                        p.encarregado AS nome_colaborador,
                        c.nome AS collaborator_name,
                        'Encarregado da programação' AS cargo,
                        'PROGRAMACAO_OPERACIONAL' AS tipo_vinculo,
                        1 AS quantidade,
                        NULL AS hora_inicio,
                        NULL AS hora_fim,
                        p.atualizado_em
                    FROM programacao_operacional p
                    LEFT JOIN colaborador c
                      ON c.id = p.encarregado_colaborador_id
                     AND c.deletado_em IS NULL
                    WHERE p.obra_id = ?
                      AND p.cancelado_em IS NULL
                      AND p.encarregado IS NOT NULL
                      AND p.encarregado <> ''
                ) equipe
                ORDER BY
                    data_rdo DESC,
                    numero_rdo,
                    cargo,
                    nome_colaborador,
                    labor_record_id
                """,
                (resultSet, rowNumber) ->
                        new TeamRecord(
                                resultSet.getString(
                                        "labor_record_id"
                                ),
                                resultSet.getString(
                                        "obra_id"
                                ),
                                resultSet.getString(
                                        "rdo_id"
                                ),
                                resultSet.getString(
                                        "numero_rdo"
                                ),
                                toLocalDate(
                                        resultSet.getDate(
                                                "data_rdo"
                                        )
                                ),
                                resultSet.getString(
                                        "rdo_status"
                                ),
                                resultSet.getString(
                                        "colaborador_id"
                                ),
                                resultSet.getString(
                                        "nome_colaborador"
                                ),
                                resultSet.getString(
                                        "collaborator_name"
                                ),
                                resultSet.getString(
                                        "cargo"
                                ),
                                resultSet.getString(
                                        "tipo_vinculo"
                                ),
                                resultSet.getBigDecimal(
                                        "quantidade"
                                ),
                                toLocalTime(
                                        resultSet.getTime(
                                                "hora_inicio"
                                        )
                                ),
                                toLocalTime(
                                        resultSet.getTime(
                                                "hora_fim"
                                        )
                                ),
                                toLocalDateTime(
                                        resultSet.getTimestamp(
                                                "atualizado_em"
                                        )
                                )
                        ),
                worksiteId.trim(),
                worksiteId.trim(),
                worksiteId.trim(),
                worksiteId.trim(),
                worksiteId.trim()
        );
    }
}

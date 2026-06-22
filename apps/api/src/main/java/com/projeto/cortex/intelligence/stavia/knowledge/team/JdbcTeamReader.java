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
                ORDER BY
                    r.data_rdo DESC,
                    r.numero_rdo,
                    mo.cargo,
                    mo.nome_colaborador,
                    mo.id
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
                worksiteId.trim()
        );
    }
}

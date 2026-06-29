package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRdoKnowledgeReader implements RdoKnowledgeReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRdoKnowledgeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<RdoKnowledgeRecord> findByWorksiteId(
            String worksiteId
    ) {
        if (worksiteId == null || worksiteId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada."
            );
        }

        return jdbcTemplate.query(
                """
                SELECT
                    r.id,
                    r.obra_id,
                    COALESCE(
                        NULLIF(o.codigo_cw, ''),
                        NULLIF(o.codigo_contrato, ''),
                        NULLIF(o.codigo_interno, ''),
                        o.id
                    ) AS codigo_obra,
                    r.programacao_id,
                    p.chave_negocio AS programacao_chave,
                    p.data_programacao AS programacao_data,
                    p.servico AS programacao_servico,
                    p.status AS programacao_status,
                    r.numero_rdo,
                    r.data_rdo,
                    r.turno,
                    r.status,
                    r.observacoes,
                    r.criado_em,
                    r.atualizado_em,
                    (
                        SELECT COUNT(*)
                        FROM rdo_mao_obra mo
                        WHERE mo.rdo_id = r.id
                    ) AS total_mao_obra,
                    (
                        SELECT COUNT(*)
                        FROM rdo_equipamento eq
                        WHERE eq.rdo_id = r.id
                    ) AS total_equipamentos,
                    (
                        SELECT COUNT(*)
                        FROM rdo_material mat
                        WHERE mat.rdo_id = r.id
                    ) AS total_materiais,
                    (
                        SELECT COUNT(*)
                        FROM rdo_controle_geometrico cg
                        WHERE cg.rdo_id = r.id
                    ) AS total_controles_geometricos
                FROM rdo r
                JOIN obra o
                  ON o.id = r.obra_id
                LEFT JOIN programacao_operacional p
                  ON p.id = r.programacao_id
                 AND p.cancelado_em IS NULL
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                ORDER BY r.data_rdo DESC, r.criado_em DESC, r.id
                """,
                (resultSet, rowNumber) ->
                        new RdoKnowledgeRecord(
                                resultSet.getString("id"),
                                resultSet.getString("obra_id"),
                                resultSet.getString("codigo_obra"),
                                resultSet.getString(
                                        "programacao_id"
                                ),
                                resultSet.getString(
                                        "programacao_chave"
                                ),
                                toLocalDate(
                                        resultSet.getDate(
                                                "programacao_data"
                                        )
                                ),
                                resultSet.getString(
                                        "programacao_servico"
                                ),
                                resultSet.getString(
                                        "programacao_status"
                                ),
                                resultSet.getString("numero_rdo"),
                                toLocalDate(
                                        resultSet.getDate("data_rdo")
                                ),
                                resultSet.getString("turno"),
                                resultSet.getString("status"),
                                resultSet.getString("observacoes"),
                                resultSet.getInt("total_mao_obra"),
                                resultSet.getInt("total_equipamentos"),
                                resultSet.getInt("total_materiais"),
                                resultSet.getInt(
                                        "total_controles_geometricos"
                                ),
                                toLocalDateTime(
                                        resultSet.getTimestamp(
                                                "criado_em"
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

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toLocalDateTime();
    }
}

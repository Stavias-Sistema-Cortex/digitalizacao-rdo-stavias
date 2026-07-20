package com.projeto.cortex.intelligence.stavia.knowledge.equipment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDate;
import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDateTime;
import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalTime;

@Component
public class JdbcEquipmentReader
        implements EquipmentReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcEquipmentReader(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<EquipmentRecord> findByWorksiteId(
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
                    eq.id AS equipment_record_id,
                    r.obra_id,
                    r.id AS rdo_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status AS rdo_status,

                    eq.asset_id,
                    eq.prefixo,
                    eq.descricao,
                    eq.tipo_equipamento,
                    eq.tipo_vinculo,
                    eq.quantidade,
                    eq.hora_inicio,
                    eq.hora_fim,
                    eq.observacoes,
                    eq.atualizado_em,

                    a.external_code AS registered_external_code,
                    a.name AS registered_name,
                    a.category AS registered_category,
                    a.active AS registered_active

                FROM rdo r

                JOIN rdo_equipamento eq
                  ON eq.rdo_id = r.id

                LEFT JOIN asset a
                  ON a.id = eq.asset_id
                 AND a.deleted_at IS NULL

                WHERE r.obra_id = ?

                ORDER BY
                    r.data_rdo DESC,
                    r.numero_rdo,
                    eq.prefixo,
                    eq.descricao,
                    eq.id
                """,
                (resultSet, rowNumber) ->
                        new EquipmentRecord(
                                resultSet.getString(
                                        "equipment_record_id"
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
                                        "asset_id"
                                ),
                                resultSet.getString(
                                        "prefixo"
                                ),
                                resultSet.getString(
                                        "descricao"
                                ),
                                resultSet.getString(
                                        "tipo_equipamento"
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
                                resultSet.getString(
                                        "observacoes"
                                ),
                                resultSet.getString(
                                        "registered_external_code"
                                ),
                                resultSet.getString(
                                        "registered_name"
                                ),
                                resultSet.getString(
                                        "registered_category"
                                ),
                                resultSet.getObject(
                                        "registered_active",
                                        Boolean.class
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

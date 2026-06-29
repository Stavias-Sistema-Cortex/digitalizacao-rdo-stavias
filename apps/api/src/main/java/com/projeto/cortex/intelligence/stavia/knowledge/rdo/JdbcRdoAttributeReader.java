package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class JdbcRdoAttributeReader implements RdoAttributeReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRdoAttributeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<RdoAttributeRecord> findByWorksiteId(
            String worksiteId,
            LocalDate startDate,
            LocalDate endDate,
            int limit
    ) {
        if (worksiteId == null || worksiteId.isBlank()) {
            return List.of();
        }

        int safeLimit = Math.max(1, Math.min(limit, 200));

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
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    r.fonte_criacao,
                    r.turno,
                    r.condicao_manha,
                    r.condicao_tarde,
                    r.condicao_noite,
                    r.pluviometria_mm,
                    r.criado_em,
                    r.atualizado_em,
                    r.enviado_em,
                    r.aprovado_em
                FROM rdo r
                JOIN obra o
                  ON o.id = r.obra_id
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                  AND (? IS NULL OR r.data_rdo >= ?)
                  AND (? IS NULL OR r.data_rdo <= ?)
                  AND (
                        r.condicao_manha IS NOT NULL
                     OR r.condicao_tarde IS NOT NULL
                     OR r.condicao_noite IS NOT NULL
                     OR r.pluviometria_mm IS NOT NULL
                     OR r.turno IS NOT NULL
                  )
                ORDER BY
                    CASE
                        WHEN r.status IN ('VALIDADO', 'VALIDADA', 'APROVADO', 'APROVADA') THEN 0
                        WHEN r.status = 'ENVIADO' THEN 1
                        ELSE 2
                    END,
                    r.data_rdo DESC,
                    COALESCE(r.aprovado_em, r.enviado_em, r.atualizado_em, r.criado_em) DESC,
                    r.id DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new RdoAttributeRecord(
                        resultSet.getString("id"),
                        resultSet.getString("obra_id"),
                        resultSet.getString("codigo_obra"),
                        resultSet.getString("numero_rdo"),
                        toLocalDate(resultSet.getDate("data_rdo")),
                        resultSet.getString("status"),
                        resultSet.getString("fonte_criacao"),
                        resultSet.getString("turno"),
                        resultSet.getString("condicao_manha"),
                        resultSet.getString("condicao_tarde"),
                        resultSet.getString("condicao_noite"),
                        resultSet.getBigDecimal("pluviometria_mm"),
                        toLocalDateTime(resultSet.getTimestamp("criado_em")),
                        toLocalDateTime(resultSet.getTimestamp("atualizado_em")),
                        toLocalDateTime(resultSet.getTimestamp("enviado_em")),
                        toLocalDateTime(resultSet.getTimestamp("aprovado_em"))
                ),
                worksiteId.trim(),
                startDate,
                startDate,
                endDate,
                endDate,
                safeLimit
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

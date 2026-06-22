package com.projeto.cortex.intelligence.stavia.knowledge.occurrence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDate;
import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDateTime;

@Component
public class JdbcOccurrenceReader
        implements OccurrenceReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOccurrenceReader(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OccurrenceRecord> findByWorksiteId(
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
                    id,
                    obra_id,
                    numero_rdo,
                    data_rdo,
                    status,
                    observacoes,
                    atualizado_em
                FROM rdo
                WHERE obra_id = ?
                  AND observacoes IS NOT NULL
                  AND TRIM(observacoes) <> ''
                ORDER BY
                    data_rdo DESC,
                    numero_rdo,
                    id
                """,
                (resultSet, rowNumber) ->
                        new OccurrenceRecord(
                                resultSet.getString("id"),
                                resultSet.getString("obra_id"),
                                resultSet.getString("numero_rdo"),
                                toLocalDate(
                                        resultSet.getDate(
                                                "data_rdo"
                                        )
                                ),
                                resultSet.getString("status"),
                                resultSet.getString("observacoes"),
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

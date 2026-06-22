package com.projeto.cortex.intelligence.stavia.knowledge.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcOperationalHistoryReader
        implements OperationalHistoryReader {

    private static final TypeReference<Map<String, Object>>
            PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcOperationalHistoryReader(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<OperationalHistoryEvent> findByWorksiteGraph(
            String worksiteId,
            int maximumDepth,
            int maximumEvents
    ) {
        if (worksiteId == null || worksiteId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada."
            );
        }

        if (maximumDepth < 0 || maximumDepth > 10) {
            throw new IllegalArgumentException(
                    "A profundidade deve estar entre 0 e 10."
            );
        }

        if (maximumEvents < 1 || maximumEvents > 1000) {
            throw new IllegalArgumentException(
                    "O limite de eventos deve estar entre 1 e 1000."
            );
        }

        return jdbcTemplate.query(
                """
                WITH RECURSIVE related_nodes AS (
                    SELECT
                        CAST(
                            'OBRA'
                            AS CHAR(80) CHARACTER SET utf8mb4
                        ) COLLATE utf8mb4_unicode_ci AS entity_type,

                        CAST(
                            ?
                            AS CHAR(36) CHARACTER SET ascii
                        ) COLLATE ascii_bin AS entity_id,

                        0 AS depth,

                        CAST(
                            CONCAT('|OBRA:', ?, '|')
                            AS CHAR(4000) CHARACTER SET utf8mb4
                        ) COLLATE utf8mb4_unicode_ci AS traversal_path

                    UNION ALL

                    SELECT
                        CASE
                            WHEN relation.origem_tipo = node.entity_type
                             AND relation.origem_id = node.entity_id
                                THEN relation.destino_tipo
                            ELSE relation.origem_tipo
                        END AS entity_type,

                        CASE
                            WHEN relation.origem_tipo = node.entity_type
                             AND relation.origem_id = node.entity_id
                                THEN relation.destino_id
                            ELSE relation.origem_id
                        END AS entity_id,

                        node.depth + 1,

                        CONCAT(
                            node.traversal_path,
                            CASE
                                WHEN relation.origem_tipo = node.entity_type
                                 AND relation.origem_id = node.entity_id
                                    THEN relation.destino_tipo
                                ELSE relation.origem_tipo
                            END,
                            ':',
                            CASE
                                WHEN relation.origem_tipo = node.entity_type
                                 AND relation.origem_id = node.entity_id
                                    THEN relation.destino_id
                                ELSE relation.origem_id
                            END,
                            '|'
                        )
                    FROM related_nodes node
                    JOIN cortex_relacao relation
                      ON relation.ativa = 1
                     AND (
                            (
                                relation.origem_tipo = node.entity_type
                                AND relation.origem_id = node.entity_id
                            )
                            OR
                            (
                                relation.destino_tipo = node.entity_type
                                AND relation.destino_id = node.entity_id
                            )
                         )
                    WHERE node.depth < ?
                      AND LOCATE(
                            CONCAT(
                                '|',
                                CASE
                                    WHEN relation.origem_tipo = node.entity_type
                                     AND relation.origem_id = node.entity_id
                                        THEN relation.destino_tipo
                                    ELSE relation.origem_tipo
                                END,
                                ':',
                                CASE
                                    WHEN relation.origem_tipo = node.entity_type
                                     AND relation.origem_id = node.entity_id
                                        THEN relation.destino_id
                                    ELSE relation.origem_id
                                END,
                                '|'
                            ),
                            node.traversal_path
                      ) = 0
                ),
                unique_nodes AS (
                    SELECT DISTINCT
                        entity_type,
                        entity_id
                    FROM related_nodes
                )
                SELECT DISTINCT
                    event.id,
                    event.commit_seq,
                    event.tipo_entidade,
                    event.entidade_id,
                    event.tipo_evento,
                    event.fonte,
                    event.payload_json,
                    event.ocorrido_em
                FROM cortex_evento_operacional event
                JOIN unique_nodes node
                  ON node.entity_type = event.tipo_entidade
                 AND node.entity_id = event.entidade_id
                ORDER BY event.commit_seq DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    Timestamp occurredAt =
                            resultSet.getTimestamp("ocorrido_em");

                    return new OperationalHistoryEvent(
                            resultSet.getString("id"),
                            resultSet.getLong("commit_seq"),
                            resultSet.getString("tipo_entidade"),
                            resultSet.getString("entidade_id"),
                            resultSet.getString("tipo_evento"),
                            resultSet.getString("fonte"),
                            occurredAt.toInstant(),
                            parsePayload(
                                    resultSet.getString(
                                            "payload_json"
                                    )
                            )
                    );
                },
                worksiteId.trim(),
                worksiteId.trim(),
                maximumDepth,
                maximumEvents
        );
    }

    private Map<String, Object> parsePayload(
            String payloadJson
    ) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(
                    payloadJson,
                    PAYLOAD_TYPE
            );
        } catch (Exception exception) {
            return Map.of(
                    "payloadInvalido",
                    true
            );
        }
    }
}

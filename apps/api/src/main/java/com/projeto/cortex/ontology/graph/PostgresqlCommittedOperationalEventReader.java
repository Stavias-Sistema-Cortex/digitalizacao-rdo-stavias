package com.projeto.cortex.ontology.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only adapter from the durable canonical ledger to graph input. */
@Repository
@Profile("postgresql")
public class PostgresqlCommittedOperationalEventReader {

    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresqlCommittedOperationalEventReader(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public OptionalLong commitSequenceForEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return OptionalLong.empty();
        }
        List<Long> values = jdbc.query(
                "SELECT commit_seq FROM cortex_evento_operacional WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                eventId.trim()
        );
        return values.isEmpty()
                ? OptionalLong.empty()
                : OptionalLong.of(values.getFirst());
    }

    public Optional<CommittedOperationalEvent> findNextAfter(
            long checkpoint,
            long targetInclusive
    ) {
        if (checkpoint < 0 || targetInclusive < 0) {
            throw new IllegalArgumentException("Graph projection bounds cannot be negative.");
        }
        if (checkpoint >= targetInclusive) {
            return Optional.empty();
        }
        List<CommittedOperationalEvent> rows = jdbc.query(
                """
                SELECT commit_seq, id, tipo_evento, tipo_entidade, entidade_id,
                       obra_id, entidades_relacionadas_json, ocorrido_em, payload_json
                FROM cortex_evento_operacional
                WHERE commit_seq > ?
                  AND commit_seq <= ?
                ORDER BY commit_seq
                LIMIT 1
                """,
                this::mapEvent,
                checkpoint,
                targetInclusive
        );
        return rows.stream().findFirst();
    }

    public long currentHighWaterMark() {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(commit_seq), 0) FROM cortex_evento_operacional",
                Long.class
        );
        return value == null ? 0L : value;
    }

    private CommittedOperationalEvent mapEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String principalType = resultSet.getString("tipo_entidade");
        String principalId = resultSet.getString("entidade_id");
        LocalDateTime occurredAt = resultSet.getObject(
                "ocorrido_em",
                LocalDateTime.class
        );
        if (occurredAt == null) {
            throw new SQLException("Canonical operational event has no occurrence time.");
        }
        return new CommittedOperationalEvent(
                resultSet.getLong("commit_seq"),
                resultSet.getString("id"),
                resultSet.getString("tipo_evento"),
                new CommittedOperationalEvent.EntityRef(principalType, principalId),
                relatedEntities(resultSet.getString("entidades_relacionadas_json")),
                occurredAt.toInstant(ZoneOffset.UTC),
                payload(resultSet.getString("payload_json")),
                resultSet.getString("obra_id")
        );
    }

    private List<CommittedOperationalEvent.EntityRef> relatedEntities(String json)
            throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode value = objectMapper.readTree(json);
            if (!value.isArray()) {
                return List.of();
            }
            List<CommittedOperationalEvent.EntityRef> related = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : value) {
                if (!item.isObject()) {
                    continue;
                }
                String type = text(item.get("tipo"));
                String id = text(item.get("id"));
                if (type == null || id == null || !seen.add(type + "\u0000" + id)) {
                    continue;
                }
                related.add(new CommittedOperationalEvent.EntityRef(type, id));
            }
            return List.copyOf(related);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Canonical related entities are invalid JSON.", exception);
        }
    }

    private Map<String, Object> payload(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode value = objectMapper.readTree(json);
            if (!value.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(value, PAYLOAD_TYPE);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new SQLException("Canonical payload is invalid JSON.", exception);
        }
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }
}

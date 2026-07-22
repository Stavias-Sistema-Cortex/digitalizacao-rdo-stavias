package com.projeto.cortex.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationalMemoryQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OperationalMemoryQueryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OperationalMemoryPageResponse memory(
            OperationalMemoryScope scope,
            OperationalMemoryFilter filter,
            Integer requestedLimit,
            Long beforeCommitSeq
    ) {
        int limit = clampLimit(requestedLimit);
        QuerySpec query = buildQuery(scope, filter, limit, beforeCommitSeq);
        List<OperationalMemoryEventResponse> rows = jdbcTemplate.query(
                query.sql(),
                this::mapEvent,
                query.parameters().toArray()
        );

        return page(rows, limit, scopeLabel(scope, filter));
    }

    QuerySpec buildQuery(
            OperationalMemoryScope scope,
            OperationalMemoryFilter filter,
            int limit,
            Long beforeCommitSeq
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    e.id,
                    e.commit_seq,
                    e.tipo_evento,
                    e.fonte,
                    e.tipo_entidade,
                    e.entidade_id,
                    e.entidades_relacionadas_json,
                    e.obra_id,
                    e.rdo_id,
                    e.colaborador_id,
                    e.ocorrido_em,
                    e.sincronizado_em,
                    e.origem,
                    e.sync_status,
                    e.schema_version,
                    e.usuario_id,
                    actor.nome AS actor_name,
                    e.dispositivo_id,
                    e.correlacao_id,
                    e.causacao_id,
                    e.estado_anterior_json,
                    e.estado_novo_json,
                    e.resultado,
                    e.erro_categoria,
                    e.payload_json
                FROM cortex_evento_operacional e
                LEFT JOIN colaborador actor ON actor.id = e.usuario_id
                WHERE 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();

        if (!scope.global()) {
            List<String> worksiteIds = scope.allowedWorksiteIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(String::trim)
                    .sorted(Comparator.naturalOrder())
                    .toList();

            sql.append(" AND (");
            if (!worksiteIds.isEmpty()) {
                sql.append("e.obra_id IN (");
                sql.append(String.join(", ", worksiteIds.stream()
                        .map(ignored -> "?")
                        .toList()));
                sql.append(") OR ");
                parameters.addAll(worksiteIds);
            }
            sql.append("(e.obra_id IS NULL AND e.usuario_id = ?))");
            parameters.add(scope.userId());
        }

        if (hasText(filter.entityType()) && hasText(filter.entityId())) {
            sql.append("""
                     AND (
                        (e.tipo_entidade = ? AND e.entidade_id = ?)
                        OR JSON_SEARCH(e.entidades_relacionadas_json, 'one', ?) IS NOT NULL
                     )
                    """);
            parameters.add(code(filter.entityType()));
            parameters.add(filter.entityId());
            parameters.add(filter.entityId());
        }

        appendTextFilter(sql, parameters, "e.obra_id", filter.obraId(), false);
        appendTextFilter(sql, parameters, "e.rdo_id", filter.rdoId(), false);
        appendTextFilter(sql, parameters, "e.usuario_id", filter.actorId(), false);
        appendTextFilter(sql, parameters, "e.tipo_evento", filter.eventType(), true);
        appendTextFilter(sql, parameters, "e.origem", filter.origin(), true);
        appendTextFilter(sql, parameters, "e.resultado", filter.result(), true);

        if (filter.from() != null) {
            sql.append(" AND e.ocorrido_em >= ?");
            parameters.add(filter.from());
        }

        if (filter.to() != null) {
            sql.append(" AND e.ocorrido_em <= ?");
            parameters.add(filter.to());
        }

        if (beforeCommitSeq != null) {
            sql.append(" AND e.commit_seq < ?");
            parameters.add(beforeCommitSeq);
        }

        sql.append(" ORDER BY e.commit_seq DESC LIMIT ?");
        parameters.add(limit + 1);

        return new QuerySpec(sql.toString(), List.copyOf(parameters));
    }

    OperationalMemoryEventResponse mapEvent(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new OperationalMemoryEventResponse(
                resultSet.getString("id"),
                resultSet.getObject("commit_seq", Long.class),
                resultSet.getString("tipo_evento"),
                resultSet.getString("fonte"),
                resultSet.getString("tipo_entidade"),
                resultSet.getString("entidade_id"),
                parseJson(
                        resultSet.getString("entidades_relacionadas_json"),
                        true
                ),
                resultSet.getString("obra_id"),
                resultSet.getString("rdo_id"),
                resultSet.getString("colaborador_id"),
                toLocalDateTime(resultSet.getTimestamp("ocorrido_em")),
                toLocalDateTime(resultSet.getTimestamp("sincronizado_em")),
                resultSet.getString("origem"),
                resultSet.getString("sync_status"),
                resultSet.getObject("schema_version", Integer.class),
                resultSet.getString("usuario_id"),
                resultSet.getString("actor_name"),
                resultSet.getString("dispositivo_id"),
                resultSet.getString("correlacao_id"),
                resultSet.getString("causacao_id"),
                parseJson(
                        resultSet.getString("estado_anterior_json"),
                        false
                ),
                parseJson(
                        resultSet.getString("estado_novo_json"),
                        false
                ),
                resultSet.getString("resultado"),
                resultSet.getString("erro_categoria"),
                parseJson(resultSet.getString("payload_json"), false)
        );
    }

    OperationalMemoryPageResponse page(
            List<OperationalMemoryEventResponse> rows,
            int limit,
            String scope
    ) {
        boolean hasMore = rows.size() > limit;
        List<OperationalMemoryEventResponse> events = List.copyOf(
                rows.subList(0, Math.min(limit, rows.size()))
        );
        Long nextBeforeCommitSeq = hasMore && !events.isEmpty()
                ? events.get(events.size() - 1).commitSeq()
                : null;

        return new OperationalMemoryPageResponse(
                events,
                nextBeforeCommitSeq,
                hasMore,
                scope,
                Instant.now()
        );
    }

    private void appendTextFilter(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            String value,
            boolean normalizeCode
    ) {
        if (!hasText(value)) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        parameters.add(normalizeCode ? code(value) : value.trim());
    }

    private String scopeLabel(
            OperationalMemoryScope scope,
            OperationalMemoryFilter filter
    ) {
        if (hasText(filter.rdoId())) {
            return "RDO";
        }
        if (hasText(filter.obraId())) {
            return "WORKSITE";
        }
        return scope.label();
    }

    private JsonNode parseJson(String json, boolean arrayFallback) {
        try {
            if (!hasText(json)) {
                return arrayFallback
                        ? objectMapper.createArrayNode()
                        : objectMapper.createObjectNode();
            }
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return arrayFallback
                    ? objectMapper.createArrayNode()
                    : objectMapper.createObjectNode();
        }
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return 50;
        }
        return Math.max(1, Math.min(100, requestedLimit));
    }

    private String code(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record QuerySpec(String sql, List<Object> parameters) {
    }
}

package com.projeto.cortex.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationalTimelineService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OperationalTimelineService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<OperationalTimelineEventResponse> timeline(
            String entityType,
            String entityId,
            String obraId,
            String rdoId,
            String colaboradorId,
            LocalDateTime from,
            LocalDateTime to,
            Integer requestedLimit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    id,
                    commit_seq,
                    tipo_evento,
                    tipo_entidade,
                    entidade_id,
                    entidades_relacionadas_json,
                    obra_id,
                    rdo_id,
                    colaborador_id,
                    ocorrido_em,
                    sincronizado_em,
                    origem,
                    sync_status,
                    schema_version,
                    payload_json
                FROM cortex_evento_operacional
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (!isBlank(entityType) && !isBlank(entityId)) {
            String normalizedType = entityType.trim().toUpperCase();
            String normalizedId = entityId.trim();
            sql.append("""
                     AND (
                        (tipo_entidade = ? AND entidade_id = ?)
                        OR EXISTS (
                            SELECT 1
                            FROM jsonb_array_elements(
                                COALESCE(entidades_relacionadas_json, '[]'::jsonb)
                            ) AS related
                            WHERE related = to_jsonb(?::text)
                               OR related ->> 'id' = ?
                        )
                     )
                    """);
            params.add(normalizedType);
            params.add(normalizedId);
            params.add(normalizedId);
            params.add(normalizedId);

            if ("OBRA".equals(normalizedType)) {
                sql.append(" AND (obra_id = ? OR obra_id IS NULL)");
                params.add(normalizedId);
            } else if ("RDO".equals(normalizedType)) {
                sql.append(" AND (rdo_id = ? OR rdo_id IS NULL)");
                params.add(normalizedId);
            } else if ("COLABORADOR".equals(normalizedType)) {
                sql.append(" AND (colaborador_id = ? OR colaborador_id IS NULL)");
                params.add(normalizedId);
            }
        }

        if (!isBlank(obraId)) {
            sql.append(" AND obra_id = ?");
            params.add(obraId.trim());
        }

        if (!isBlank(rdoId)) {
            sql.append(" AND rdo_id = ?");
            params.add(rdoId.trim());
        }

        if (!isBlank(colaboradorId)) {
            sql.append(" AND colaborador_id = ?");
            params.add(colaboradorId.trim());
        }

        if (from != null) {
            sql.append(" AND ocorrido_em >= ?");
            params.add(from);
        }

        if (to != null) {
            sql.append(" AND ocorrido_em <= ?");
            params.add(to);
        }

        sql.append(" ORDER BY ocorrido_em DESC, commit_seq DESC LIMIT ?");
        params.add(limit(requestedLimit));

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new OperationalTimelineEventResponse(
                        rs.getString("id"),
                        rs.getObject("commit_seq", Long.class),
                        rs.getString("tipo_evento"),
                        rs.getString("tipo_entidade"),
                        rs.getString("entidade_id"),
                        parseJson(rs.getString("entidades_relacionadas_json"), true),
                        rs.getString("obra_id"),
                        rs.getString("rdo_id"),
                        rs.getString("colaborador_id"),
                        toLocalDateTime(rs.getTimestamp("ocorrido_em")),
                        toLocalDateTime(rs.getTimestamp("sincronizado_em")),
                        rs.getString("origem"),
                        rs.getString("sync_status"),
                        rs.getObject("schema_version", Integer.class),
                        parseJson(rs.getString("payload_json"), false)
                ),
                params.toArray()
        );
    }

    private JsonNode parseJson(String json, boolean arrayFallback) {
        try {
            if (json == null || json.isBlank()) {
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

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return 200;
        }

        return Math.max(1, Math.min(500, requestedLimit));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

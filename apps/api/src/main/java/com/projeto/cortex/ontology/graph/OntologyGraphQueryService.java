package com.projeto.cortex.ontology.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only PostgreSQL queries for the independent operational graph. */
@Service
@Transactional(readOnly = true, timeout = 5)
public class OntologyGraphQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String AUTHORITATIVE_RELATIONS = """
            'BELONGS_TO_WORKSITE', 'HAS_RDO', 'HAS_ACTIVITY',
            'HAS_COLLABORATOR', 'HAS_ASSET', 'RECORDED_IN',
            'PARTICIPATES_IN', 'USED_IN', 'EXECUTES_SERVICE', 'PRICED_BY', 'PRICES'
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OntologyGraphQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<String> resolveWorksiteId(String entityId) {
        if (!hasText(entityId)) {
            return Optional.empty();
        }

        String sql = """
                WITH RECURSIVE entity_scope(entity_id, obra_id, depth, path) AS (
                    SELECT entity.id,
                           CASE
                               WHEN UPPER(entity.entity_type) IN ('OBRA', 'WORKSITE')
                                AND LOWER(COALESCE(entity.external_ref_type, '')) = 'obra'
                                   THEN NULLIF(entity.external_ref_id, '')
                               ELSE COALESCE(
                                   NULLIF(entity.metadata_json ->> 'obraId', ''),
                                   NULLIF(entity.metadata_json ->> 'worksiteId', '')
                               )
                           END,
                           0,
                           ARRAY[entity.id]::varchar[]
                    FROM ontology_entities entity
                    WHERE entity.id = ?

                    UNION ALL

                    SELECT related.id,
                           CASE
                               WHEN UPPER(related.entity_type) IN ('OBRA', 'WORKSITE')
                                AND LOWER(COALESCE(related.external_ref_type, '')) = 'obra'
                                   THEN NULLIF(related.external_ref_id, '')
                               ELSE COALESCE(
                                   NULLIF(related.metadata_json ->> 'obraId', ''),
                                   NULLIF(related.metadata_json ->> 'worksiteId', '')
                               )
                           END,
                           scope.depth + 1,
                           scope.path || related.id
                    FROM entity_scope scope
                    JOIN ontology_relations relation
                      ON (relation.source_entity_id = scope.entity_id
                          OR relation.target_entity_id = scope.entity_id)
                     AND relation.relation_type IN (%s)
                    JOIN ontology_entities related
                      ON related.id = CASE
                          WHEN relation.source_entity_id = scope.entity_id
                              THEN relation.target_entity_id
                          ELSE relation.source_entity_id
                      END
                    WHERE scope.obra_id IS NULL
                      AND scope.depth < 3
                      AND NOT related.id = ANY(scope.path)
                )
                SELECT obra_id
                FROM entity_scope
                WHERE obra_id IS NOT NULL
                ORDER BY depth
                LIMIT 1
                """.formatted(AUTHORITATIVE_RELATIONS);
        List<String> results = jdbc.queryForList(sql, String.class, entityId.trim());
        return results.stream().filter(this::hasText).findFirst();
    }

    public List<GraphEntity> listEntities(
            String obraId,
            String type,
            String query,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT entity.*
                FROM ontology_entities entity
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addWorksitePredicate(sql, params, "entity.id", obraId);

        if (hasText(type)) {
            sql.append(" AND UPPER(entity.entity_type) = UPPER(?)");
            params.add(type.trim());
        }
        if (hasText(query)) {
            sql.append("""
                     AND (
                        LOWER(entity.canonical_name) LIKE ?
                        OR LOWER(COALESCE(entity.description, '')) LIKE ?
                        OR LOWER(COALESCE(entity.external_ref_id, '')) LIKE ?
                        OR LOWER(entity.id) LIKE ?
                     )
                    """);
            String pattern = "%" + query.trim().toLowerCase() + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY entity.updated_at DESC, entity.canonical_name, entity.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapEntity, params.toArray());
    }

    public Optional<GraphEntity> findEntity(String id) {
        if (!hasText(id)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT entity.*
                    FROM ontology_entities entity
                    WHERE entity.id = ?
                    """, this::mapEntity, id.trim()));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<GraphRelation> listRelations(
            String obraId,
            String entityId,
            String type,
            int depth,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (hasText(entityId) && depth > 1) {
            sql.append("""
                    WITH RECURSIVE reachable(entity_id, depth, path) AS (
                        SELECT ?::varchar, 0, ARRAY[?::varchar]
                        UNION ALL
                        SELECT CASE
                                   WHEN edge.source_entity_id = reachable.entity_id
                                       THEN edge.target_entity_id
                                   ELSE edge.source_entity_id
                               END,
                               reachable.depth + 1,
                               reachable.path || CASE
                                   WHEN edge.source_entity_id = reachable.entity_id
                                       THEN edge.target_entity_id
                                   ELSE edge.source_entity_id
                               END
                        FROM reachable
                        JOIN ontology_relations edge
                          ON edge.source_entity_id = reachable.entity_id
                          OR edge.target_entity_id = reachable.entity_id
                        WHERE reachable.depth < ?
                          AND NOT (CASE
                              WHEN edge.source_entity_id = reachable.entity_id
                                  THEN edge.target_entity_id
                              ELSE edge.source_entity_id
                          END) = ANY(reachable.path)
                    )
                    SELECT DISTINCT relation.*
                    FROM ontology_relations relation
                    JOIN reachable
                      ON reachable.depth < ?
                     AND (relation.source_entity_id = reachable.entity_id
                          OR relation.target_entity_id = reachable.entity_id)
                    WHERE 1 = 1
                    """);
            params.add(entityId.trim());
            params.add(entityId.trim());
            params.add(depth);
            params.add(depth);
        } else {
            sql.append("""
                    SELECT relation.*
                    FROM ontology_relations relation
                    WHERE 1 = 1
                    """);
            if (hasText(entityId)) {
                sql.append(" AND (relation.source_entity_id = ? OR relation.target_entity_id = ?)");
                params.add(entityId.trim());
                params.add(entityId.trim());
            }
        }
        addWorksitePredicate(sql, params, "relation.source_entity_id", obraId);
        addWorksitePredicate(sql, params, "relation.target_entity_id", obraId);
        if (hasText(type)) {
            sql.append(" AND UPPER(relation.relation_type) = UPPER(?)");
            params.add(type.trim());
        }
        sql.append(" ORDER BY relation.created_at DESC, relation.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapRelation, params.toArray());
    }

    public List<GraphEvent> listEvents(
            String obraId,
            String entityId,
            String type,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT event.*
                FROM ontology_events event
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addWorksitePredicate(sql, params, "event.entity_id", obraId);
        if (hasText(obraId)) {
            sql.append(" AND (event.related_entity_id IS NULL OR ");
            addWorksiteExpression(sql, params, "event.related_entity_id", obraId);
            sql.append(")");
        }
        if (hasText(entityId)) {
            sql.append(" AND (event.entity_id = ? OR event.related_entity_id = ?)");
            params.add(entityId.trim());
            params.add(entityId.trim());
        }
        if (hasText(type)) {
            sql.append(" AND UPPER(event.event_type) = UPPER(?)");
            params.add(type.trim());
        }
        sql.append(" ORDER BY event.occurred_at DESC, event.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapEvent, params.toArray());
    }

    public List<GraphState> listStates(
            String obraId,
            String entityId,
            String type,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT state.*
                FROM operational_states state
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addWorksitePredicate(sql, params, "state.entity_id", obraId);
        addEntityAndTypeFilters(sql, params, "state.entity_id", entityId, "state.state_type", type);
        sql.append(" ORDER BY state.valid_from DESC, state.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapState, params.toArray());
    }

    public List<GraphEvidence> listEvidences(
            String obraId,
            String entityId,
            String type,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT evidence.*
                FROM operational_evidences evidence
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addWorksitePredicate(sql, params, "evidence.entity_id", obraId);
        addEntityAndTypeFilters(
                sql,
                params,
                "evidence.entity_id",
                entityId,
                "evidence.evidence_type",
                type
        );
        sql.append(" ORDER BY evidence.created_at DESC, evidence.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapEvidence, params.toArray());
    }

    private void addWorksitePredicate(
            StringBuilder sql,
            List<Object> params,
            String entityIdExpression,
            String obraId
    ) {
        if (hasText(obraId)) {
            sql.append(" AND ");
            addWorksiteExpression(sql, params, entityIdExpression, obraId);
        }
    }

    private void addWorksiteExpression(
            StringBuilder sql,
            List<Object> params,
            String entityIdExpression,
            String obraId
    ) {
        sql.append("""
                EXISTS (
                    WITH RECURSIVE scoped_worksite(entity_id, obra_id, depth, path) AS (
                        SELECT scoped_entity.id,
                               CASE
                                   WHEN UPPER(scoped_entity.entity_type) IN ('OBRA', 'WORKSITE')
                                    AND LOWER(COALESCE(scoped_entity.external_ref_type, '')) = 'obra'
                                       THEN NULLIF(scoped_entity.external_ref_id, '')
                                   ELSE COALESCE(
                                       NULLIF(scoped_entity.metadata_json ->> 'obraId', ''),
                                       NULLIF(scoped_entity.metadata_json ->> 'worksiteId', '')
                                   )
                               END,
                               0,
                               ARRAY[scoped_entity.id]::varchar[]
                        FROM ontology_entities scoped_entity
                        WHERE scoped_entity.id = %s

                        UNION ALL

                        SELECT related.id,
                               CASE
                                   WHEN UPPER(related.entity_type) IN ('OBRA', 'WORKSITE')
                                    AND LOWER(COALESCE(related.external_ref_type, '')) = 'obra'
                                       THEN NULLIF(related.external_ref_id, '')
                                   ELSE COALESCE(
                                       NULLIF(related.metadata_json ->> 'obraId', ''),
                                       NULLIF(related.metadata_json ->> 'worksiteId', '')
                                   )
                               END,
                               scope.depth + 1,
                               scope.path || related.id
                        FROM scoped_worksite scope
                        JOIN ontology_relations scope_relation
                          ON (scope_relation.source_entity_id = scope.entity_id
                              OR scope_relation.target_entity_id = scope.entity_id)
                         AND scope_relation.relation_type IN (%s)
                        JOIN ontology_entities related
                          ON related.id = CASE
                              WHEN scope_relation.source_entity_id = scope.entity_id
                                  THEN scope_relation.target_entity_id
                              ELSE scope_relation.source_entity_id
                          END
                        WHERE scope.obra_id IS NULL
                          AND scope.depth < 3
                          AND NOT related.id = ANY(scope.path)
                    )
                    SELECT 1
                    FROM scoped_worksite
                    WHERE obra_id = ?
                )
                """.formatted(entityIdExpression, AUTHORITATIVE_RELATIONS));
        String normalized = obraId.trim();
        params.add(normalized);
    }

    private void addEntityAndTypeFilters(
            StringBuilder sql,
            List<Object> params,
            String entityColumn,
            String entityId,
            String typeColumn,
            String type
    ) {
        if (hasText(entityId)) {
            sql.append(" AND ").append(entityColumn).append(" = ?");
            params.add(entityId.trim());
        }
        if (hasText(type)) {
            sql.append(" AND UPPER(").append(typeColumn).append(") = UPPER(?)");
            params.add(type.trim());
        }
    }

    private void addPage(List<Object> params, int page, int size) {
        params.add(size);
        params.add((long) page * size);
    }

    private GraphEntity mapEntity(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GraphEntity(
                resultSet.getString("id"),
                resultSet.getString("entity_type"),
                resultSet.getString("external_ref_type"),
                resultSet.getString("external_ref_id"),
                resultSet.getString("canonical_name"),
                resultSet.getString("description"),
                resultSet.getString("status"),
                readMap(resultSet.getObject("metadata_json")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private GraphRelation mapRelation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GraphRelation(
                resultSet.getString("id"),
                resultSet.getString("source_entity_id"),
                resultSet.getString("relation_type"),
                resultSet.getString("target_entity_id"),
                resultSet.getBigDecimal("confidence"),
                readMap(resultSet.getObject("metadata_json")),
                instant(resultSet.getTimestamp("created_at"))
        );
    }

    private GraphEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GraphEvent(
                resultSet.getString("id"),
                resultSet.getString("event_type"),
                resultSet.getString("entity_id"),
                resultSet.getString("related_entity_id"),
                resultSet.getString("source_type"),
                resultSet.getString("source_id"),
                resultSet.getString("description"),
                readMap(resultSet.getObject("payload_json")),
                instant(resultSet.getTimestamp("occurred_at")),
                instant(resultSet.getTimestamp("created_at"))
        );
    }

    private GraphState mapState(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GraphState(
                resultSet.getString("id"),
                resultSet.getString("entity_id"),
                resultSet.getString("state_type"),
                resultSet.getString("state_value"),
                resultSet.getBigDecimal("numeric_value"),
                resultSet.getString("unit"),
                instant(resultSet.getTimestamp("valid_from")),
                instant(resultSet.getTimestamp("valid_to")),
                resultSet.getString("source_event_id"),
                readMap(resultSet.getObject("metadata_json"))
        );
    }

    private GraphEvidence mapEvidence(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GraphEvidence(
                resultSet.getString("id"),
                resultSet.getString("entity_id"),
                resultSet.getString("evidence_type"),
                resultSet.getString("source_type"),
                resultSet.getString("source_id"),
                resultSet.getString("description"),
                resultSet.getString("file_ref"),
                readMap(resultSet.getObject("metadata_json")),
                instant(resultSet.getTimestamp("created_at"))
        );
    }

    private Map<String, Object> readMap(Object value) throws SQLException {
        if (value == null) {
            return Map.of();
        }
        String json = value instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : value.toString();
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException exception) {
            throw new SQLException("Ontology graph JSON could not be read.", exception);
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    /**
     * Relations followed from source to target while resolving the source's worksite scope.
     *
     * <p>The projector models an RDO as belonging to a worksite, a collaborator/asset as
     * participating in an RDO, and an execution as recorded in an RDO. Those dependents inherit
     * the target scope. The inverse direction is deliberately forbidden: an exclusive RDO must
     * never inherit another worksite by walking back through a shared collaborator or asset.
     */
    private static final String FORWARD_SCOPE_RELATIONS = """
            'BELONGS_TO_WORKSITE', 'PARTICIPATES_IN', 'USED_IN',
            'RECORDED_IN', 'EXECUTED_IN'
            """;

    /**
     * Relations followed from target to source while resolving the target's worksite scope.
     *
     * <p>Services and price versions may be shared and therefore accumulate the scopes of the
     * executions that use them. USES_ASSET is the container-to-member spelling of USED_IN and is
     * therefore resolved in the opposite direction. Generic HAS_* relations are intentionally
     * absent: the operational projector does not emit them as worksite provenance.
     */
    private static final String REVERSE_SCOPE_RELATIONS = """
            'EXECUTES_SERVICE', 'PRICED_BY', 'PRICES', 'USES_ASSET',
            'GENERATES_REVENUE'
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OntologyGraphQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, Set<String>> resolveWorksiteIds(Set<String> entityIds) {
        Set<String> normalizedIds = normalized(entityIds);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> resolved = new LinkedHashMap<>();
        normalizedIds.forEach(id -> resolved.put(id, new LinkedHashSet<>()));

        String placeholders = placeholders(normalizedIds.size());
        String sql = """
                WITH RECURSIVE entity_scope(root_id, entity_id, obra_id, depth, path) AS (
                    SELECT entity.id,
                           entity.id,
                           %s,
                           0,
                           ARRAY[entity.id]::varchar[]
                    FROM ontology_entities entity
                    LEFT JOIN ontology_entity_worksite_snapshots entity_snapshot
                      ON entity_snapshot.entity_id = entity.id
                    WHERE entity.id IN (%s)

                    UNION ALL

                    SELECT scope.root_id,
                           related.id,
                           %s,
                           scope.depth + 1,
                           scope.path || related.id
                    FROM entity_scope scope
                    %s
                    LEFT JOIN ontology_entity_worksite_snapshots related_snapshot
                      ON related_snapshot.entity_id = related.id
                    WHERE scope.depth < 3
                      AND NOT related.id = ANY(scope.path)
                )
                SELECT DISTINCT root_id, obra_id
                FROM entity_scope
                WHERE obra_id IS NOT NULL
                ORDER BY root_id, obra_id
                """.formatted(
                authoritativeWorksiteExpression("entity", "entity_snapshot"),
                placeholders,
                authoritativeWorksiteExpression("related", "related_snapshot"),
                scopeTraversalJoins("scope", "relation", "related")
        );
        jdbc.query(sql, resultSet -> {
            while (resultSet.next()) {
                String rootId = resultSet.getString("root_id");
                String worksiteId = resultSet.getString("obra_id");
                if (hasText(rootId) && hasText(worksiteId)) {
                    resolved.computeIfAbsent(rootId, ignored -> new LinkedHashSet<>())
                            .add(worksiteId);
                }
            }
            return null;
        }, normalizedIds.toArray());
        resolved.replaceAll((ignored, worksiteIds) -> Set.copyOf(worksiteIds));
        return Map.copyOf(resolved);
    }

    public List<GraphEntity> listEntitiesScoped(
            Set<String> worksiteIds,
            String type,
            String query,
            int page,
            int size
    ) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT entity.* FROM (\n");
        sql.append(entityView(worksiteIds, params));
        sql.append("\n) entity WHERE 1 = 1");
        addWorksitePredicate(sql, params, "entity.id", worksiteIds);

        if (hasText(type)) {
            sql.append(" AND UPPER(entity.entity_type) = UPPER(?)");
            params.add(type.trim());
        }
        if (hasText(query)) {
            sql.append("""
                     AND (
                        LOWER(entity.canonical_name) LIKE ? ESCAPE '\\'
                        OR LOWER(COALESCE(entity.description, '')) LIKE ? ESCAPE '\\'
                        OR LOWER(COALESCE(entity.external_ref_id, '')) LIKE ? ESCAPE '\\'
                        OR LOWER(entity.id) LIKE ? ESCAPE '\\'
                     )
                    """);
            String pattern = literalContainsPattern(query);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY entity.updated_at DESC, entity.canonical_name, entity.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapEntity, params.toArray());
    }

    private String authoritativeWorksiteExpression(
            String entityAlias,
            String snapshotAlias
    ) {
        return """
                CASE
                    WHEN UPPER(%1$s.entity_type) IN ('OBRA', 'WORKSITE')
                     AND LOWER(COALESCE(%1$s.external_ref_type, '')) = 'obra'
                        THEN NULLIF(%1$s.external_ref_id, '')
                    ELSE %2$s.source_worksite_id
                END
                """.formatted(entityAlias, snapshotAlias);
    }

    public Optional<GraphEntity> findEntity(String id) {
        return findEntityScoped(id, null);
    }

    public Optional<GraphEntity> findEntityScoped(String id, Set<String> worksiteIds) {
        if (!hasText(id)) {
            return Optional.empty();
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT entity.* FROM (\n");
        sql.append(entityView(worksiteIds, params));
        sql.append("\n) entity WHERE entity.id = ?");
        params.add(id.trim());
        addWorksitePredicate(sql, params, "entity.id", worksiteIds);
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    sql.toString(),
                    this::mapEntity,
                    params.toArray()
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private String entityView(Set<String> worksiteIds, List<Object> params) {
        if (worksiteIds == null) {
            return """
                    SELECT raw_entity.*
                    FROM ontology_entities raw_entity
                    """;
        }
        Set<String> normalizedWorksiteIds = normalized(worksiteIds);
        String snapshotScope;
        if (normalizedWorksiteIds.isEmpty()) {
            snapshotScope = "1 = 0";
        } else {
            snapshotScope = "snapshot.source_worksite_id IN ("
                    + placeholders(normalizedWorksiteIds.size()) + ")";
            params.addAll(normalizedWorksiteIds);
        }
        return """
                SELECT
                    raw_entity.id,
                    raw_entity.entity_type,
                    raw_entity.external_ref_type,
                    raw_entity.external_ref_id,
                    COALESCE(
                        scoped_snapshot.canonical_name,
                        NULLIF(raw_entity.external_ref_id, ''),
                        raw_entity.id
                    ) AS canonical_name,
                    scoped_snapshot.description,
                    scoped_snapshot.status,
                    COALESCE(scoped_snapshot.metadata_json, '{}'::jsonb) AS metadata_json,
                    COALESCE(scoped_snapshot.created_at, raw_entity.created_at) AS created_at,
                    COALESCE(scoped_snapshot.updated_at, raw_entity.created_at) AS updated_at
                FROM ontology_entities raw_entity
                LEFT JOIN LATERAL (
                    SELECT snapshot.*
                    FROM ontology_entity_worksite_snapshots snapshot
                    WHERE snapshot.entity_id = raw_entity.id
                      AND %s
                    ORDER BY snapshot.updated_at DESC, snapshot.source_worksite_id
                    LIMIT 1
                ) scoped_snapshot ON TRUE
                """.formatted(snapshotScope);
    }

    public List<GraphRelation> listRelationsScoped(
            Set<String> worksiteIds,
            String entityId,
            String type,
            int depth,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (hasText(entityId) && depth > 1) {
            StringBuilder traversalScopeSql = new StringBuilder("1 = 1");
            List<Object> traversalScopeParams = new ArrayList<>();
            if (worksiteIds != null) {
                traversalScopeSql.setLength(0);
                addWorksiteExpression(
                        traversalScopeSql,
                        traversalScopeParams,
                        """
                        CASE
                            WHEN edge.source_entity_id = reachable.entity_id
                                THEN edge.target_entity_id
                            ELSE edge.source_entity_id
                        END
                        """,
                        worksiteIds
                );
            }
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
                          AND %s
                    )
                    SELECT DISTINCT relation.*
                    FROM ontology_relations relation
                    JOIN reachable
                      ON reachable.depth < ?
                     AND (relation.source_entity_id = reachable.entity_id
                          OR relation.target_entity_id = reachable.entity_id)
                    WHERE 1 = 1
                    """.formatted(traversalScopeSql));
            params.add(entityId.trim());
            params.add(entityId.trim());
            params.add(depth);
            params.addAll(traversalScopeParams);
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
        addWorksitePredicate(sql, params, "relation.source_entity_id", worksiteIds);
        addWorksitePredicate(sql, params, "relation.target_entity_id", worksiteIds);
        if (hasText(type)) {
            sql.append(" AND UPPER(relation.relation_type) = UPPER(?)");
            params.add(type.trim());
        }
        sql.append(" ORDER BY relation.created_at DESC, relation.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapRelation, params.toArray());
    }

    public List<GraphEvent> listEventsScoped(
            Set<String> worksiteIds,
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
        addWorksitePredicate(sql, params, "event.entity_id", worksiteIds);
        addSourceWorksitePredicate(
                sql,
                params,
                "event.source_worksite_id",
                worksiteIds
        );
        if (worksiteIds != null) {
            sql.append(" AND (event.related_entity_id IS NULL OR ");
            addWorksiteExpression(sql, params, "event.related_entity_id", worksiteIds);
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

    public List<GraphState> listStatesScoped(
            Set<String> worksiteIds,
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
        addWorksitePredicate(sql, params, "state.entity_id", worksiteIds);
        addSourceWorksitePredicate(
                sql,
                params,
                "state.source_worksite_id",
                worksiteIds
        );
        addEntityAndTypeFilters(sql, params, "state.entity_id", entityId, "state.state_type", type);
        sql.append(" ORDER BY state.valid_from DESC, state.id LIMIT ? OFFSET ?");
        addPage(params, page, size);
        return jdbc.query(sql.toString(), this::mapState, params.toArray());
    }

    public List<GraphEvidence> listEvidencesScoped(
            Set<String> worksiteIds,
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
        addWorksitePredicate(sql, params, "evidence.entity_id", worksiteIds);
        addSourceWorksitePredicate(
                sql,
                params,
                "evidence.source_worksite_id",
                worksiteIds
        );
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
            Set<String> worksiteIds
    ) {
        if (worksiteIds != null) {
            sql.append(" AND ");
            addWorksiteExpression(sql, params, entityIdExpression, worksiteIds);
        }
    }

    private void addSourceWorksitePredicate(
            StringBuilder sql,
            List<Object> params,
            String sourceWorksiteColumn,
            Set<String> worksiteIds
    ) {
        if (worksiteIds == null) {
            return;
        }
        Set<String> normalizedWorksiteIds = normalized(worksiteIds);
        if (normalizedWorksiteIds.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND NULLIF(%s, '') IN (%s)".formatted(
                sourceWorksiteColumn,
                placeholders(normalizedWorksiteIds.size())
        ));
        params.addAll(normalizedWorksiteIds);
    }

    private void addWorksiteExpression(
            StringBuilder sql,
            List<Object> params,
            String entityIdExpression,
            Set<String> worksiteIds
    ) {
        Set<String> normalizedWorksiteIds = normalized(worksiteIds);
        if (normalizedWorksiteIds.isEmpty()) {
            sql.append("1 = 0");
            return;
        }
        sql.append("""
                EXISTS (
                    WITH RECURSIVE scoped_worksite(entity_id, obra_id, depth, path) AS (
                        SELECT scoped_entity.id,
                               %s,
                               0,
                               ARRAY[scoped_entity.id]::varchar[]
                        FROM ontology_entities scoped_entity
                        LEFT JOIN ontology_entity_worksite_snapshots scoped_snapshot
                          ON scoped_snapshot.entity_id = scoped_entity.id
                        WHERE scoped_entity.id = %s

                        UNION ALL

                        SELECT related.id,
                               %s,
                               scope.depth + 1,
                               scope.path || related.id
                        FROM scoped_worksite scope
                        %s
                        LEFT JOIN ontology_entity_worksite_snapshots related_snapshot
                          ON related_snapshot.entity_id = related.id
                        WHERE scope.depth < 3
                          AND NOT related.id = ANY(scope.path)
                    )
                    SELECT 1
                    FROM scoped_worksite
                    WHERE obra_id IN (%s)
                )
                """.formatted(
                authoritativeWorksiteExpression("scoped_entity", "scoped_snapshot"),
                entityIdExpression,
                authoritativeWorksiteExpression("related", "related_snapshot"),
                scopeTraversalJoins("scope", "scope_relation", "related"),
                placeholders(normalizedWorksiteIds.size())
        ));
        params.addAll(normalizedWorksiteIds);
    }

    /** One direction/type policy shared by batch authorization and pre-pagination SQL scope. */
    private String scopeTraversalJoins(
            String scopeAlias,
            String relationAlias,
            String relatedAlias
    ) {
        return """
                JOIN ontology_relations %2$s
                  ON (
                       (%2$s.source_entity_id = %1$s.entity_id
                        AND UPPER(%2$s.relation_type) IN (%4$s))
                       OR
                       (%2$s.target_entity_id = %1$s.entity_id
                        AND UPPER(%2$s.relation_type) IN (%5$s))
                  )
                JOIN ontology_entities %3$s
                  ON %3$s.id = CASE
                      WHEN %2$s.source_entity_id = %1$s.entity_id
                          THEN %2$s.target_entity_id
                      ELSE %2$s.source_entity_id
                  END
                """.formatted(
                scopeAlias,
                relationAlias,
                relatedAlias,
                FORWARD_SCOPE_RELATIONS,
                REVERSE_SCOPE_RELATIONS
        );
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

    private Set<String> normalized(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new TreeSet<>();
        for (String value : values) {
            if (hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return normalized;
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private String literalContainsPattern(String query) {
        String escaped = query.trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
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

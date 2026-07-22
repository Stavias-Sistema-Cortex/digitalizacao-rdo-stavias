package com.projeto.cortex.ontology;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.projeto.cortex.sync.OperationalEventVisibilityPolicy;

@Service
public class OperationalMemoryQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final Set<String> PII_ENTITY_TYPES = Set.of(
            "ACTOR",
            "COLABORADOR",
            "COLLABORATOR",
            "PERSON",
            "PESSOA",
            "USER",
            "USUARIO"
    );

    private final JdbcTemplate jdbcTemplate;
    private final OperationalMemoryCursorSigner cursorSigner;

    public OperationalMemoryQueryService(
            JdbcTemplate jdbcTemplate,
            OperationalMemoryCursorSigner cursorSigner
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.cursorSigner = cursorSigner;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationalMemoryPageResponse search(
            OperationalMemoryScope scope,
            OperationalMemoryFilter filter,
            Integer requestedLimit,
            OperationalMemoryCursor cursor
    ) {
        if (scope == null || filter == null) {
            throw new IllegalArgumentException("Operational Memory scope and filter are required.");
        }
        validateDirectRequest(filter);

        int limit = boundedLimit(requestedLimit);
        String scopeFingerprint = scopeHash(scope);
        String filterFingerprint = filterHash(filter);
        PagePosition position = cursor == null
                ? null
                : pagePosition(cursorSigner.verify(
                        cursor.token(), scopeFingerprint, filterFingerprint
                ));
        CoverageSnapshot snapshot = coverage(
                scope,
                position == null ? null : position.highWaterMark()
        );
        long highWaterMark = position == null
                ? snapshot.newestCommitSequence()
                : position.highWaterMark();
        requireCursorInScope(scope, position, highWaterMark);
        QuerySpec query = buildSearchQuery(
                scope,
                filter,
                limit,
                position,
                highWaterMark
        );
        List<OperationalMemoryEventResponse> rows = jdbcTemplate.query(
                query.sql(),
                this::mapEvent,
                query.parameters().toArray()
        );
        boolean hasMore = rows.size() > limit;
        List<OperationalMemoryEventResponse> items = List.copyOf(
                rows.subList(0, Math.min(limit, rows.size()))
        );
        OperationalMemoryCursor nextCursor = hasMore && !items.isEmpty()
                ? new OperationalMemoryCursor(cursorSigner.sign(
                        highWaterMark,
                        items.getLast().commitSequence(),
                        items.getLast().eventId(),
                        scopeFingerprint,
                        filterFingerprint
                ))
                : null;

        return new OperationalMemoryPageResponse(
                items,
                nextCursor,
                hasMore,
                highWaterMark,
                scopeFingerprint,
                new OperationalMemoryCoverage(
                        "FULL_HISTORY",
                        true,
                        snapshot.authorizedEventCount(),
                        snapshot.oldestCommitSequence(),
                        snapshot.newestCommitSequence()
                ),
                Instant.now()
        );
    }

    QuerySpec buildSearchQuery(
            OperationalMemoryScope scope,
            OperationalMemoryFilter filter,
            int limit,
            PagePosition cursor,
            long highWaterMark
    ) {
        boolean hasSearch = hasText(filter.query());
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        if (hasSearch) {
            sql.append("""
                    WITH memory_query AS (
                        SELECT
                            websearch_to_tsquery('portuguese', CAST(? AS text)) AS ts_query,
                            lower(CAST(? AS text)) AS needle,
                            CAST(? AS text) AS contains_pattern
                    )
                    """);
            parameters.add(filter.query());
            parameters.add(filter.query());
            parameters.add(literalContainsPattern(filter.query()));
        }

        sql.append("""
                SELECT
                    e.id,
                    e.commit_seq,
                    e.tipo_evento,
                    e.fonte,
                    e.tipo_entidade,
                    e.entidade_id,
                    e.obra_id,
                    worksite.nome AS worksite_name,
                    e.rdo_id,
                    report.numero_rdo,
                    principal.canonical_name AS principal_name,
                    left(coalesce(
                        nullif(e.payload_json ->> 'servicoNome', ''),
                        nullif(e.payload_json ->> 'serviceName', ''),
                        nullif(e.payload_json ->> 'nomeServico', '')
                    ), 255) AS service_name,
                    e.ocorrido_em,
                    e.sincronizado_em,
                    e.origem,
                    e.sync_status,
                    e.schema_version,
                    e.resultado,
                    e.erro_categoria,
                """);
        if (hasSearch) {
            sql.append("""
                    (
                        ts_rank_cd(e.search_document, search.ts_query)
                        + CASE WHEN lower(e.id) = search.needle THEN 10.0 ELSE 0.0 END
                        + CASE
                            WHEN NOT (
                                upper(e.tipo_entidade) IN (
                                    'ACTOR', 'COLABORADOR', 'COLLABORATOR', 'PERSON',
                                    'PESSOA', 'USER', 'USUARIO'
                                )
                            ) AND lower(e.entidade_id) = search.needle
                                THEN 8.0 ELSE 0.0
                          END
                        + CASE
                            WHEN lower(coalesce(principal.id, '')) = search.needle
                              OR lower(coalesce(principal.external_ref_id, '')) = search.needle
                                THEN 8.0 ELSE 0.0
                          END
                        + CASE WHEN lower(coalesce(e.obra_id, '')) = search.needle
                            THEN 7.0 ELSE 0.0 END
                        + CASE WHEN lower(coalesce(e.rdo_id, '')) = search.needle
                            THEN 7.0 ELSE 0.0 END
                        + greatest(
                            similarity(e.search_text, search.needle),
                            similarity(lower(coalesce(worksite.nome, '')), search.needle),
                            similarity(lower(coalesce(report.numero_rdo, '')), search.needle),
                            similarity(lower(coalesce(principal.canonical_name, '')), search.needle)
                          )
                    )::double precision AS relevance
                    """);
        } else {
            sql.append("0.0::double precision AS relevance\n");
        }

        sql.append("""
                FROM cortex_evento_operacional e
                LEFT JOIN obra worksite ON worksite.id = e.obra_id
                LEFT JOIN rdo report ON report.id = e.rdo_id
                LEFT JOIN LATERAL (
                    SELECT entity.id, entity.external_ref_id, entity.canonical_name
                    FROM ontology_entities entity
                    WHERE upper(e.tipo_entidade) NOT IN (
                              'ACTOR', 'COLABORADOR', 'COLLABORATOR', 'PERSON',
                              'PESSOA', 'USER', 'USUARIO'
                          )
                      AND upper(entity.entity_type) NOT IN (
                              'ACTOR', 'COLABORADOR', 'COLLABORATOR', 'PERSON',
                              'PESSOA', 'USER', 'USUARIO'
                          )
                      AND (
                          entity.id = e.entidade_id
                          OR (
                              entity.external_ref_id = e.entidade_id
                              AND lower(coalesce(entity.external_ref_type, '')) =
                                  lower(e.tipo_entidade)
                          )
                      )
                    ORDER BY CASE WHEN entity.id = e.entidade_id THEN 0 ELSE 1 END,
                             entity.id
                    LIMIT 1
                ) principal ON true
                """);
        if (hasSearch) {
            sql.append("CROSS JOIN memory_query search\n");
        }
        sql.append("WHERE e.commit_seq <= ?\n");
        parameters.add(highWaterMark);
        appendScopePredicate(sql, parameters, scope, "e");
        appendFilters(sql, parameters, filter);

        if (hasSearch) {
            sql.append("""
                     AND (
                        e.search_document @@ search.ts_query
                        OR e.search_text LIKE search.contains_pattern ESCAPE '\\'
                        OR to_tsvector(
                            'portuguese', coalesce(worksite.nome, '')
                        ) @@ search.ts_query
                        OR lower(coalesce(worksite.nome, ''))
                            LIKE search.contains_pattern ESCAPE '\\'
                        OR to_tsvector(
                            'portuguese', coalesce(report.numero_rdo, '')
                        ) @@ search.ts_query
                        OR lower(coalesce(report.numero_rdo, ''))
                            LIKE search.contains_pattern ESCAPE '\\'
                        OR to_tsvector(
                            'portuguese', coalesce(principal.canonical_name, '')
                        ) @@ search.ts_query
                        OR lower(coalesce(principal.canonical_name, ''))
                            LIKE search.contains_pattern ESCAPE '\\'
                        OR lower(coalesce(principal.id, ''))
                            LIKE search.contains_pattern ESCAPE '\\'
                        OR lower(coalesce(principal.external_ref_id, ''))
                            LIKE search.contains_pattern ESCAPE '\\'
                     )
                    """);
        }

        if (cursor != null) {
            sql.append("""
                     AND (
                        e.commit_seq < ?
                        OR (e.commit_seq = ? AND e.id < ?)
                     )
                    """);
            parameters.add(cursor.commitSequence());
            parameters.add(cursor.commitSequence());
            parameters.add(cursor.eventId());
        }

        sql.append(" ORDER BY e.commit_seq DESC, e.id DESC LIMIT ?");
        parameters.add(limit + 1);
        return new QuerySpec(sql.toString(), List.copyOf(parameters));
    }

    private CoverageSnapshot coverage(
            OperationalMemoryScope scope,
            Long highWaterMark
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    COUNT(*) AS authorized_event_count,
                    MIN(e.commit_seq) AS oldest_commit_sequence,
                    COALESCE(MAX(e.commit_seq), 0) AS newest_commit_sequence
                FROM cortex_evento_operacional e
                WHERE 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();
        if (highWaterMark != null) {
            sql.append(" AND e.commit_seq <= ?");
            parameters.add(highWaterMark);
        }
        appendScopePredicate(sql, parameters, scope, "e");
        return jdbcTemplate.query(
                sql.toString(),
                resultSet -> {
                    if (!resultSet.next()) {
                        return new CoverageSnapshot(0L, null, 0L);
                    }
                    long count = resultSet.getLong("authorized_event_count");
                    Long oldest = resultSet.getObject("oldest_commit_sequence", Long.class);
                    long newest = resultSet.getLong("newest_commit_sequence");
                    return new CoverageSnapshot(count, oldest, newest);
                },
                parameters.toArray()
        );
    }

    private void requireCursorInScope(
            OperationalMemoryScope scope,
            PagePosition cursor,
            long highWaterMark
    ) {
        if (cursor == null) {
            return;
        }
        StringBuilder sql = new StringBuilder("""
                SELECT EXISTS (
                    SELECT 1
                    FROM cortex_evento_operacional e
                    WHERE e.commit_seq = ?
                      AND e.id = ?
                      AND e.commit_seq <= ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(cursor.commitSequence());
        parameters.add(cursor.eventId());
        parameters.add(highWaterMark);
        appendScopePredicate(sql, parameters, scope, "e");
        sql.append(")");
        Boolean allowed = jdbcTemplate.queryForObject(
                sql.toString(),
                Boolean.class,
                parameters.toArray()
        );
        if (!Boolean.TRUE.equals(allowed)) {
            throw new OperationalMemoryCursorScopeException();
        }
    }

    private void appendScopePredicate(
            StringBuilder sql,
            List<Object> parameters,
            OperationalMemoryScope scope,
            String alias
    ) {
        OperationalEventVisibilityPolicy.SqlPredicate predicate =
                OperationalEventVisibilityPolicy.forMemory(
                        scope.global(),
                        scope.allowedWorksiteIds(),
                        scope.financialWorksiteIds(),
                        scope.financialUnitIds(),
                        scope.userId(),
                        alias
                );
        sql.append(predicate.sql());
        parameters.addAll(predicate.parameters());
    }

    private void appendFilters(
            StringBuilder sql,
            List<Object> parameters,
            OperationalMemoryFilter filter
    ) {
        if (hasText(filter.entityType()) && hasText(filter.entityId())) {
            sql.append("""
                     AND (
                        (upper(e.tipo_entidade) = ? AND e.entidade_id = ?)
                        OR EXISTS (
                            SELECT 1
                            FROM jsonb_array_elements(
                                CASE
                                    WHEN jsonb_typeof(e.entidades_relacionadas_json) = 'array'
                                        THEN e.entidades_relacionadas_json
                                    ELSE '[]'::jsonb
                                END
                            ) related
                            WHERE related ->> 'id' = ?
                              AND upper(coalesce(
                                  related ->> 'type', related ->> 'tipo', ''
                              )) = ?
                        )
                     )
                    """);
            parameters.add(filter.entityType());
            parameters.add(filter.entityId());
            parameters.add(filter.entityId());
            parameters.add(filter.entityType());
        }
        appendExact(sql, parameters, "e.obra_id", filter.worksiteId());
        appendExact(sql, parameters, "e.rdo_id", filter.rdoId());
        appendExact(sql, parameters, "e.usuario_id", filter.actorId());
        appendExact(sql, parameters, "upper(e.tipo_evento)", filter.eventType());
        appendExact(sql, parameters, "upper(e.origem)", filter.origin());
        appendExact(sql, parameters, "upper(e.resultado)", filter.result());
        if (filter.from() != null) {
            sql.append(" AND e.ocorrido_em >= ?");
            parameters.add(LocalDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
        }
        if (filter.to() != null) {
            sql.append(" AND e.ocorrido_em <= ?");
            parameters.add(LocalDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
        }
    }

    private void appendExact(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            String value
    ) {
        if (hasText(value)) {
            sql.append(" AND ").append(column).append(" = ?");
            parameters.add(value);
        }
    }

    private OperationalMemoryEventResponse mapEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String entityType = resultSet.getString("tipo_entidade");
        String safeEntityId = isPiiEntity(entityType)
                ? null
                : resultSet.getString("entidade_id");
        return new OperationalMemoryEventResponse(
                resultSet.getString("id"),
                resultSet.getLong("commit_seq"),
                resultSet.getString("tipo_evento"),
                resultSet.getString("fonte"),
                entityType,
                safeEntityId,
                isPiiEntity(entityType) ? null : resultSet.getString("principal_name"),
                resultSet.getString("obra_id"),
                resultSet.getString("worksite_name"),
                resultSet.getString("rdo_id"),
                resultSet.getString("numero_rdo"),
                resultSet.getString("service_name"),
                utcInstant(resultSet.getObject("ocorrido_em", LocalDateTime.class)),
                utcInstant(resultSet.getObject("sincronizado_em", LocalDateTime.class)),
                resultSet.getString("origem"),
                resultSet.getString("sync_status"),
                resultSet.getInt("schema_version"),
                resultSet.getString("resultado"),
                resultSet.getString("erro_categoria"),
                resultSet.getDouble("relevance")
        );
    }

    private Instant utcInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private String scopeHash(OperationalMemoryScope scope) {
        List<String> fields = new ArrayList<>();
        fields.add(scope.userId());
        fields.add(Boolean.toString(scope.global()));
        appendSortedSet(fields, scope.allowedWorksiteIds());
        appendSortedSet(fields, scope.financialWorksiteIds());
        appendSortedSet(fields, scope.financialUnitIds());
        fields.add(conversationVisibilityFingerprint(scope));
        return fingerprint("operational-memory-scope-v2", fields);
    }

    private void appendSortedSet(List<String> fields, Set<String> values) {
        TreeSet<String> sortedValues = new TreeSet<>(values);
        fields.add(Integer.toString(sortedValues.size()));
        fields.addAll(sortedValues);
    }

    private String conversationVisibilityFingerprint(OperationalMemoryScope scope) {
        if (scope.global()) {
            return "GLOBAL";
        }
        OperationalEventVisibilityPolicy.SqlPredicate predicate =
                OperationalEventVisibilityPolicy.activeConversationVisibility(
                        "cv",
                        scope.userId()
                );
        List<String> conversationIds = jdbcTemplate.queryForList(
                "SELECT cv.id FROM conversa cv WHERE 1 = 1"
                        + predicate.sql()
                        + " ORDER BY cv.id",
                String.class,
                predicate.parameters().toArray()
        );
        return fingerprint("operational-memory-conversations-v1", conversationIds);
    }

    String filterHash(OperationalMemoryFilter filter) {
        List<String> fields = new ArrayList<>();
        fields.add(filter.query());
        fields.add(filter.entityType());
        fields.add(filter.entityId());
        fields.add(filter.worksiteId());
        fields.add(filter.rdoId());
        fields.add(filter.actorId());
        fields.add(filter.eventType());
        fields.add(filter.origin());
        fields.add(filter.result());
        fields.add(filter.from() == null ? null : filter.from().toString());
        fields.add(filter.to() == null ? null : filter.to().toString());
        return fingerprint("operational-memory-filter-v2", fields);
    }

    private String fingerprint(String domain, List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFingerprintField(digest, domain);
            for (String field : fields) {
                updateFingerprintField(digest, field);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void updateFingerprintField(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private int boundedLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    }

    private void validateDirectRequest(OperationalMemoryFilter filter) {
        if (filter.query() != null && filter.query().length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("Operational Memory query is too long.");
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new IllegalArgumentException("Operational Memory UTC range is invalid.");
        }
    }

    private String literalContainsPattern(String query) {
        String escaped = query.trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private boolean isPiiEntity(String entityType) {
        return entityType != null
                && PII_ENTITY_TYPES.contains(entityType.trim().toUpperCase(Locale.ROOT));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record QuerySpec(String sql, List<Object> parameters) {
    }

    private PagePosition pagePosition(
            OperationalMemoryCursorSigner.VerifiedCursor cursor
    ) {
        return new PagePosition(
                cursor.highWaterMark(),
                cursor.commitSequence(),
                cursor.eventId()
        );
    }

    private record PagePosition(
            long highWaterMark,
            long commitSequence,
            String eventId
    ) {
    }

    private record CoverageSnapshot(
            long authorizedEventCount,
            Long oldestCommitSequence,
            long newestCommitSequence
    ) {
    }
}

final class OperationalMemoryCursorScopeException extends IllegalArgumentException {

    OperationalMemoryCursorScopeException() {
        super("Operational Memory cursor does not belong to the authorized scope.");
    }
}

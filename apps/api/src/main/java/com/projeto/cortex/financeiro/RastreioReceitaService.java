package com.projeto.cortex.financeiro;

import com.projeto.cortex.ontology.OperationalMemoryCursorSigner;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RastreioReceitaService {

    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 500;
    private static final long MAX_PERIOD_DAYS = 365;

    private final JdbcTemplate jdbc;
    private final OperationalMemoryCursorSigner cursorSigner;

    public RastreioReceitaService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public RastreioReceitaService(
            JdbcTemplate jdbc,
            OperationalMemoryCursorSigner cursorSigner
    ) {
        this.jdbc = jdbc;
        this.cursorSigner = cursorSigner;
    }

    public RastreioReceitaResponse buscar(
            Set<String> allowedObraIds,
            String obraId,
            LocalDate de,
            LocalDate ate
    ) {
        return buscar(allowedObraIds, obraId, de, ate, null, null);
    }

    public RastreioReceitaResponse buscar(
            Set<String> allowedObraIds,
            String obraId,
            LocalDate de,
            LocalDate ate,
            String cursor,
            Integer limit
    ) {
        LocalDate effectiveTo;
        LocalDate effectiveFrom;
        try {
            effectiveTo = effectiveTo(de, ate);
            effectiveFrom = effectiveFrom(de, effectiveTo);
        } catch (DateTimeException exception) {
            throw error(HttpStatus.BAD_REQUEST, "REVENUE_TRACE_PERIOD_INVALID");
        }
        validatePeriod(effectiveFrom, effectiveTo);
        List<String> scope = normalizedScope(allowedObraIds);
        if (obraId != null && !obraId.isBlank()) {
            String selectedWorksite = uuid(obraId, "REVENUE_TRACE_WORKSITE_INVALID");
            if (!scope.contains(selectedWorksite)) {
                throw error(
                        HttpStatus.FORBIDDEN, "REVENUE_TRACE_WORKSITE_FORBIDDEN"
                );
            }
            scope = List.of(selectedWorksite);
        }
        if (scope.isEmpty()) {
            return new RastreioReceitaResponse(
                    effectiveFrom, effectiveTo, BigDecimal.ZERO, 0, List.of(),
                    null, "COMPLETE", 0L
            );
        }

        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw error(HttpStatus.BAD_REQUEST, "REVENUE_TRACE_LIMIT_INVALID");
        }
        String scopeHash = scopeHash(scope);
        String filterHash = filterHash(effectiveFrom, effectiveTo);
        RevenueCursor decoded = decodeCursor(cursor, scopeHash, filterHash);
        long highWaterMark = decoded == null
                ? currentHighWaterMark()
                : decoded.highWaterMark();
        List<RastreioReceitaResponse.RevenueEvidenceRow> rows = queryRows(
                scope, effectiveFrom, effectiveTo, null,
                highWaterMark, decoded, pageSize + 1
        );
        boolean hasNext = rows.size() > pageSize;
        List<RastreioReceitaResponse.RevenueEvidenceRow> pageRows = hasNext
                ? List.copyOf(rows.subList(0, pageSize))
                : List.copyOf(rows);
        BigDecimal total = rows.stream()
                .limit(pageSize)
                .map(RastreioReceitaResponse.RevenueEvidenceRow::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String nextCursor = hasNext
                ? encodeCursor(
                        pageRows.getLast(), scopeHash, filterHash, highWaterMark
                )
                : null;
        return new RastreioReceitaResponse(
                effectiveFrom, effectiveTo, total, pageRows.size(), pageRows,
                nextCursor, hasNext ? "PARTIAL" : "COMPLETE", highWaterMark
        );
    }

    public RastreioReceitaEvidenceResponse evidencia(
            Set<String> allowedObraIds,
            String executionId
    ) {
        String normalizedExecution = uuid(
                executionId, "REVENUE_TRACE_EXECUTION_INVALID"
        );
        List<String> scope = normalizedScope(allowedObraIds);
        if (scope.isEmpty()) {
            throw notFoundOrForbidden();
        }
        List<RastreioReceitaResponse.RevenueEvidenceRow> rows = queryRows(
                scope, null, null, normalizedExecution,
                null, null, 2
        );
        if (rows.size() != 1) {
            throw notFoundOrForbidden();
        }
        RastreioReceitaResponse.RevenueEvidenceRow row = rows.getFirst();
        List<RastreioReceitaEvidenceResponse.OntologyLink> links =
                persistedOntologyLinks(row);
        if (links.size() != 4) {
            throw notFoundOrForbidden();
        }
        return new RastreioReceitaEvidenceResponse(
                row, links
        );
    }

    private List<RastreioReceitaResponse.RevenueEvidenceRow> queryRows(
            List<String> scope,
            LocalDate from,
            LocalDate to,
            String executionId,
            Long highWaterMark,
            RevenueCursor cursor,
            int rowLimit
    ) {
        String placeholders = String.join(
                ",", scope.stream().map(ignored -> "?").toList()
        );
        StringBuilder sql = new StringBuilder("""
                SELECT execution.obra_id AS worksite_id,
                       worksite.nome AS worksite_name,
                       execution.rdo_id,
                       rdo.numero_rdo,
                       execution.id AS execution_id,
                       execution.data_execucao,
                       execution.service_id,
                       service.codigo AS service_code,
                       service.nome AS service_name,
                       execution.price_version_id,
                       price.versao AS price_version,
                       execution.quantidade_executada,
                       execution.unidade_medida,
                       execution.unit_price_snapshot,
                       execution.currency,
                       execution.revenue_amount,
                       execution.revenue_coverage_code,
                       execution.revenue_evidence_id,
                       execution.revenue_event_id,
                       event.commit_seq,
                       execution.accepted_at
                FROM execucao_servico_rdo execution
                JOIN obra worksite ON worksite.id = execution.obra_id
                JOIN rdo ON rdo.id = execution.rdo_id
                JOIN catalogo_servico service ON service.id = execution.service_id
                JOIN service_price_version price
                  ON price.id = execution.price_version_id
                 AND price.obra_id = execution.obra_id
                 AND price.service_id = execution.service_id
                """);
        sql.append(CanonicalRevenueEvidenceSql.CANONICAL_EVENT_JOIN)
                .append(" WHERE execution.obra_id IN (")
                .append(placeholders)
                .append(") AND ")
                .append(CanonicalRevenueEvidenceSql.ELIGIBLE_EXECUTION_PREDICATE)
                .append(" AND ")
                .append(CanonicalRevenueEvidenceSql.ACCEPTED_EVIDENCE_PREDICATE);

        List<Object> args = new ArrayList<>(scope);
        if (from != null) {
            sql.append(" AND execution.data_execucao >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND execution.data_execucao <= ?");
            args.add(to);
        }
        if (executionId != null) {
            sql.append(" AND execution.id = ?");
            args.add(executionId);
        }
        if (highWaterMark != null) {
            sql.append(" AND event.commit_seq <= ?");
            args.add(highWaterMark);
        }
        if (cursor != null) {
            sql.append("""
                     AND (
                         event.commit_seq < ?
                         OR (
                             event.commit_seq = ?
                             AND execution.id < ?
                         )
                     )
                    """);
            args.add(cursor.lastCommitSequence());
            args.add(cursor.lastCommitSequence());
            args.add(cursor.lastExecutionId());
        }
        sql.append(" ORDER BY event.commit_seq DESC, execution.id DESC LIMIT ")
                .append(rowLimit);

        return jdbc.query(
                sql.toString(),
                (rs, rowNumber) -> new RastreioReceitaResponse.RevenueEvidenceRow(
                        rs.getString("worksite_id"),
                        rs.getString("worksite_name"),
                        rs.getString("rdo_id"),
                        rs.getString("numero_rdo"),
                        rs.getString("execution_id"),
                        rs.getDate("data_execucao").toLocalDate(),
                        rs.getString("service_id"),
                        rs.getString("service_code"),
                        rs.getString("service_name"),
                        rs.getString("price_version_id"),
                        rs.getInt("price_version"),
                        rs.getBigDecimal("quantidade_executada"),
                        rs.getString("unidade_medida"),
                        rs.getBigDecimal("unit_price_snapshot"),
                        rs.getString("currency"),
                        rs.getBigDecimal("revenue_amount"),
                        rs.getString("revenue_coverage_code"),
                        rs.getString("revenue_evidence_id"),
                        rs.getString("revenue_event_id"),
                        rs.getLong("commit_seq"),
                        instant(rs.getTimestamp("accepted_at"))
                ),
                args.toArray()
        );
    }

    private long currentHighWaterMark() {
        Long value = jdbc.queryForObject("""
                SELECT ultima_commit_seq
                FROM cortex_evento_commit_sequence
                WHERE id = 1
                """, Long.class);
        return value == null ? 0L : Math.max(0L, value);
    }

    private RevenueCursor decodeCursor(
            String token,
            String expectedScopeHash,
            String expectedFilterHash
    ) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() > 2_048) {
            throw invalidCursor();
        }
        try {
            if (cursorSigner != null) {
                OperationalMemoryCursorSigner.VerifiedCursor verified =
                        cursorSigner.verify(
                                token,
                                revenueScopeFingerprint(expectedScopeHash),
                                revenueFilterFingerprint(expectedFilterHash)
                        );
                return new RevenueCursor(
                        verified.highWaterMark(),
                        verified.commitSequence(),
                        UUID.fromString(verified.eventId()).toString()
                );
            }
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length > 512) {
                throw invalidCursor();
            }
            String[] fields = new String(bytes, StandardCharsets.UTF_8)
                    .split("\\n", -1);
            if (fields.length != 5
                    || !"v1".equals(fields[0])
                    || !sha256(
                            expectedScopeHash + ":" + expectedFilterHash
                    ).equals(fields[1])) {
                throw invalidCursor();
            }
            long highWaterMark = Long.parseLong(fields[2]);
            long lastCommitSequence = Long.parseLong(fields[3]);
            if (
                highWaterMark < 0L ||
                lastCommitSequence < 0L ||
                lastCommitSequence > highWaterMark
            ) {
                throw invalidCursor();
            }
            return new RevenueCursor(
                    highWaterMark,
                    lastCommitSequence,
                    UUID.fromString(fields[4]).toString()
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private String encodeCursor(
            RastreioReceitaResponse.RevenueEvidenceRow lastRow,
            String scopeHash,
            String filterHash,
            long highWaterMark
    ) {
        if (cursorSigner != null) {
            return cursorSigner.sign(
                    highWaterMark,
                    lastRow.eventCommitSequence(),
                    lastRow.executionId(),
                    revenueScopeFingerprint(scopeHash),
                    revenueFilterFingerprint(filterHash)
            );
        }
        String value = String.join(
                "\n",
                "v1",
                sha256(scopeHash + ":" + filterHash),
                Long.toString(highWaterMark),
                Long.toString(lastRow.eventCommitSequence()),
                lastRow.executionId()
        );
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String scopeHash(List<String> scope) {
        return sha256(String.join(",", scope));
    }

    private String filterHash(
            LocalDate from,
            LocalDate to
    ) {
        return sha256(String.join(
                "\n",
                from.toString(),
                to.toString()
        ));
    }

    private String revenueScopeFingerprint(String scopeHash) {
        return "revenue-scope-v1:" + scopeHash;
    }

    private String revenueFilterFingerprint(String filterHash) {
        return "revenue-filter-v1:" + filterHash;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 indisponível.", impossible
            );
        }
    }

    private ResponseStatusException invalidCursor() {
        return error(HttpStatus.BAD_REQUEST, "REVENUE_TRACE_CURSOR_INVALID");
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw error(HttpStatus.BAD_REQUEST, "REVENUE_TRACE_PERIOD_INVALID");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) {
            throw error(
                    HttpStatus.BAD_REQUEST, "REVENUE_TRACE_PERIOD_TOO_LARGE"
            );
        }
    }

    private LocalDate effectiveTo(LocalDate from, LocalDate to) {
        if (to != null) {
            return to;
        }
        return from == null ? LocalDate.now() : from.plusDays(MAX_PERIOD_DAYS);
    }

    private LocalDate effectiveFrom(LocalDate from, LocalDate effectiveTo) {
        return from == null ? effectiveTo.minusDays(MAX_PERIOD_DAYS) : from;
    }

    private List<String> normalizedScope(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> uuid(value, "REVENUE_TRACE_SCOPE_INVALID"))
                .distinct()
                .sorted()
                .toList();
    }

    private List<RastreioReceitaEvidenceResponse.OntologyLink> persistedOntologyLinks(
            RastreioReceitaResponse.RevenueEvidenceRow row
    ) {
        List<PersistedOntologyLink> persisted = jdbc.query("""
                SELECT relation.origem_tipo,
                       relation.origem_id,
                       relation.tipo_relacao,
                       relation.destino_tipo,
                       relation.destino_id,
                       relation.ativa,
                       relation.fonte AS relation_source,
                       source.id AS source_object_id,
                       source.tabela_entidade AS source_table,
                       source.status AS source_status,
                       source.fonte AS source_registry_source,
                       source.metadados_json ->> 'rdoId' AS source_rdo_id,
                       source.metadados_json ->> 'obraId' AS source_worksite_id,
                       target.id AS target_object_id,
                       target.tabela_entidade AS target_table,
                       target.status AS target_status,
                       target.fonte AS target_registry_source,
                       target.metadados_json ->> 'executionId'
                           AS target_execution_id,
                       target.metadados_json ->> 'rdoId' AS target_rdo_id,
                       target.metadados_json ->> 'obraId' AS target_worksite_id
                FROM cortex_relacao relation
                LEFT JOIN cortex_objeto source
                  ON source.tipo_entidade = relation.origem_tipo
                 AND source.entidade_id = relation.origem_id
                LEFT JOIN cortex_objeto target
                  ON target.tipo_entidade = relation.destino_tipo
                 AND target.entidade_id = relation.destino_id
                WHERE relation.origem_tipo = 'RDO_SERVICE_EXECUTED'
                  AND relation.origem_id = ?
                  AND relation.ativa = TRUE
                ORDER BY CASE relation.tipo_relacao
                    WHEN 'EXECUTED_IN' THEN 1
                    WHEN 'EXECUTES_SERVICE' THEN 2
                    WHEN 'GENERATES_REVENUE' THEN 3
                    WHEN 'PRICED_BY' THEN 4
                    ELSE 5
                END,
                relation.tipo_relacao,
                relation.destino_tipo,
                relation.destino_id
                """, (rs, rowNumber) -> new PersistedOntologyLink(
                rs.getString("origem_tipo"),
                rs.getString("origem_id"),
                rs.getString("tipo_relacao"),
                rs.getString("destino_tipo"),
                rs.getString("destino_id"),
                rs.getBoolean("ativa"),
                rs.getString("relation_source"),
                rs.getString("source_object_id"),
                rs.getString("source_table"),
                rs.getString("source_status"),
                rs.getString("source_registry_source"),
                rs.getString("source_rdo_id"),
                rs.getString("source_worksite_id"),
                rs.getString("target_object_id"),
                rs.getString("target_table"),
                rs.getString("target_status"),
                rs.getString("target_registry_source"),
                rs.getString("target_execution_id"),
                rs.getString("target_rdo_id"),
                rs.getString("target_worksite_id")
        ), row.executionId());

        if (!validOntologyChain(persisted, row)) {
            return List.of();
        }
        return persisted.stream().map(link ->
                new RastreioReceitaEvidenceResponse.OntologyLink(
                        link.sourceType(),
                        link.sourceId(),
                        link.relationType(),
                        link.targetType(),
                        link.targetId(),
                        link.active()
                )
        ).toList();
    }

    private boolean validOntologyChain(
            List<PersistedOntologyLink> links,
            RastreioReceitaResponse.RevenueEvidenceRow row
    ) {
        if (links.size() != 4) {
            return false;
        }
        for (PersistedOntologyLink link : links) {
            if (!validSource(link, row) || !validTarget(link, row)) {
                return false;
            }
        }
        return links.get(0).matches(
                "EXECUTED_IN", "RDO", row.rdoId()
        ) && links.get(1).matches(
                "EXECUTES_SERVICE", "SERVICE", row.serviceId()
        ) && links.get(2).matches(
                "GENERATES_REVENUE",
                "REVENUE_EVIDENCE",
                row.revenueEvidenceId()
        ) && links.get(3).matches(
                "PRICED_BY",
                "SERVICE_PRICE_VERSION",
                row.priceVersionId()
        );
    }

    private boolean validSource(
            PersistedOntologyLink link,
            RastreioReceitaResponse.RevenueEvidenceRow row
    ) {
        return link.active()
                && "CORTEX_FINANCEIRO".equals(link.relationSource())
                && "RDO_SERVICE_EXECUTED".equals(link.sourceType())
                && row.executionId().equals(link.sourceId())
                && link.sourceObjectId() != null
                && "execucao_servico_rdo".equals(link.sourceTable())
                && "ACCEPTED".equals(link.sourceStatus())
                && "CORTEX_FINANCEIRO".equals(link.sourceRegistrySource())
                && row.rdoId().equals(link.sourceRdoId())
                && row.worksiteId().equals(link.sourceWorksiteId());
    }

    private boolean validTarget(
            PersistedOntologyLink link,
            RastreioReceitaResponse.RevenueEvidenceRow row
    ) {
        if (link.targetObjectId() == null) {
            return false;
        }
        return switch (link.relationType()) {
            case "EXECUTED_IN" ->
                    "rdo".equals(link.targetTable());
            case "EXECUTES_SERVICE" ->
                    "catalogo_servico".equals(link.targetTable())
                            && "CORTEX_FINANCEIRO".equals(
                            link.targetRegistrySource()
                    );
            case "PRICED_BY" ->
                    "service_price_version".equals(link.targetTable())
                            && "CORTEX_FINANCEIRO".equals(
                            link.targetRegistrySource()
                    );
            case "GENERATES_REVENUE" ->
                    "execucao_servico_rdo".equals(link.targetTable())
                            && "ACCEPTED".equals(link.targetStatus())
                            && "CORTEX_FINANCEIRO".equals(
                            link.targetRegistrySource()
                    )
                            && row.executionId().equals(
                            link.targetExecutionId()
                    )
                            && row.rdoId().equals(link.targetRdoId())
                            && row.worksiteId().equals(
                            link.targetWorksiteId()
                    );
            default -> false;
        };
    }

    private String uuid(String raw, String errorCode) {
        if (raw == null || raw.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, errorCode);
        }
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw error(HttpStatus.BAD_REQUEST, errorCode);
        }
    }

    private java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private ResponseStatusException notFoundOrForbidden() {
        return error(
                HttpStatus.NOT_FOUND,
                "REVENUE_EVIDENCE_NOT_FOUND_OR_FORBIDDEN"
        );
    }

    private ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }

    private record PersistedOntologyLink(
            String sourceType,
            String sourceId,
            String relationType,
            String targetType,
            String targetId,
            boolean active,
            String relationSource,
            String sourceObjectId,
            String sourceTable,
            String sourceStatus,
            String sourceRegistrySource,
            String sourceRdoId,
            String sourceWorksiteId,
            String targetObjectId,
            String targetTable,
            String targetStatus,
            String targetRegistrySource,
            String targetExecutionId,
            String targetRdoId,
            String targetWorksiteId
    ) {
        private boolean matches(
                String expectedRelation,
                String expectedTargetType,
                String expectedTargetId
        ) {
            return expectedRelation.equals(relationType)
                    && expectedTargetType.equals(targetType)
                    && expectedTargetId.equals(targetId);
        }
    }

    private record RevenueCursor(
            long highWaterMark,
            long lastCommitSequence,
            String lastExecutionId
    ) {
    }
}

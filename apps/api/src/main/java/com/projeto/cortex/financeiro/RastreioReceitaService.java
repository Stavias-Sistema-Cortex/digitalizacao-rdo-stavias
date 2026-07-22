package com.projeto.cortex.financeiro;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RastreioReceitaService {

    private static final int MAX_RESULT_ROWS = 500;
    private static final long MAX_PERIOD_DAYS = 365;

    private final JdbcTemplate jdbc;

    public RastreioReceitaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RastreioReceitaResponse buscar(
            Set<String> allowedObraIds,
            String obraId,
            LocalDate de,
            LocalDate ate
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
                    effectiveFrom, effectiveTo, BigDecimal.ZERO, 0, List.of()
            );
        }

        List<RastreioReceitaResponse.RevenueEvidenceRow> rows = queryRows(
                scope, effectiveFrom, effectiveTo, null
        );
        if (rows.size() > MAX_RESULT_ROWS) {
            throw error(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "REVENUE_TRACE_RESULT_LIMIT_EXCEEDED"
            );
        }
        BigDecimal total = rows.stream()
                .map(RastreioReceitaResponse.RevenueEvidenceRow::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RastreioReceitaResponse(
                effectiveFrom, effectiveTo, total, rows.size(), List.copyOf(rows)
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
                scope, null, null, normalizedExecution
        );
        if (rows.size() != 1) {
            throw notFoundOrForbidden();
        }
        RastreioReceitaResponse.RevenueEvidenceRow row = rows.getFirst();
        return new RastreioReceitaEvidenceResponse(
                row, canonicalLinks(row)
        );
    }

    private List<RastreioReceitaResponse.RevenueEvidenceRow> queryRows(
            List<String> scope,
            LocalDate from,
            LocalDate to,
            String executionId
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
                JOIN cortex_evento_operacional event
                  ON event.id = execution.revenue_event_id
                 AND event.tipo_entidade = 'RDO_EXECUTION'
                 AND event.tipo_evento = 'RDO_SERVICE_EXECUTED'
                 AND event.entidade_id = execution.id
                 AND event.obra_id = execution.obra_id
                 AND event.rdo_id = execution.rdo_id
                 AND event.payload_json ->> 'schemaVersion' = '1'
                 AND event.payload_json ->> 'status' = 'ACCEPTED'
                 AND event.payload_json ->> 'rdoId' = execution.rdo_id
                 AND event.payload_json ->> 'obraId' = execution.obra_id
                 AND event.payload_json ->> 'serviceId' = execution.service_id
                 AND event.payload_json ->> 'priceVersionId' = execution.price_version_id
                 AND event.payload_json ->> 'revenueEvidenceId' = execution.revenue_evidence_id
                 AND event.payload_json ->> 'unit' = execution.unidade_medida
                 AND event.payload_json ->> 'currency' = execution.currency
                 AND CASE
                     WHEN event.payload_json ->> 'acceptedQuantity'
                          ~ '^[0-9]+([.][0-9]+)?$'
                     THEN (event.payload_json ->> 'acceptedQuantity')::numeric
                          = execution.quantidade_executada
                     ELSE FALSE
                 END
                 AND CASE
                     WHEN event.payload_json ->> 'unitPrice'
                          ~ '^[0-9]+([.][0-9]+)?$'
                     THEN (event.payload_json ->> 'unitPrice')::numeric
                          = execution.unit_price_snapshot
                     ELSE FALSE
                 END
                 AND CASE
                     WHEN event.payload_json ->> 'revenue'
                          ~ '^[0-9]+([.][0-9]+)?$'
                     THEN (event.payload_json ->> 'revenue')::numeric
                          = execution.revenue_amount
                     ELSE FALSE
                 END
                 AND jsonb_typeof(event.entidades_relacionadas_json) = 'array'
                 AND jsonb_array_length(event.entidades_relacionadas_json) = 5
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object('tipo', 'RDO', 'id', execution.rdo_id)
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object(
                         'tipo', 'WORKSITE', 'id', execution.obra_id
                     )
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object(
                         'tipo', 'SERVICE', 'id', execution.service_id
                     )
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object(
                         'tipo', 'SERVICE_PRICE_VERSION',
                         'id', execution.price_version_id
                     )
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object(
                         'tipo', 'REVENUE_EVIDENCE',
                         'id', execution.revenue_evidence_id
                     )
                 )
                WHERE execution.obra_id IN (""");
        sql.append(placeholders).append(")")
                .append(" AND execution.revenue_coverage_code = 'ACCEPTED_EXACT'")
                .append(" AND execution.revenue_evidence_id IS NOT NULL")
                .append(" AND execution.revenue_event_id IS NOT NULL")
                .append(" AND execution.cancelada = FALSE");

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
        sql.append(" ORDER BY execution.data_execucao DESC, ")
                .append("execution.accepted_at DESC, execution.id")
                .append(executionId == null ? " LIMIT 501" : " LIMIT 2");

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

    private List<RastreioReceitaEvidenceResponse.OntologyLink> canonicalLinks(
            RastreioReceitaResponse.RevenueEvidenceRow row
    ) {
        String sourceType = "RDO_SERVICE_EXECUTED";
        return List.of(
                new RastreioReceitaEvidenceResponse.OntologyLink(
                        sourceType, row.executionId(), "EXECUTED_IN",
                        "RDO", row.rdoId(), true
                ),
                new RastreioReceitaEvidenceResponse.OntologyLink(
                        sourceType, row.executionId(), "EXECUTES_SERVICE",
                        "SERVICE", row.serviceId(), true
                ),
                new RastreioReceitaEvidenceResponse.OntologyLink(
                        sourceType, row.executionId(), "GENERATES_REVENUE",
                        "REVENUE_EVIDENCE", row.revenueEvidenceId(), true
                ),
                new RastreioReceitaEvidenceResponse.OntologyLink(
                        sourceType, row.executionId(), "PRICED_BY",
                        "SERVICE_PRICE_VERSION", row.priceVersionId(), true
                )
        );
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
}

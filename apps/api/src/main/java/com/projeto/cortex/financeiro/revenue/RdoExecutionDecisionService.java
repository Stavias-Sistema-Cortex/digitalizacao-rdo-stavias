package com.projeto.cortex.financeiro.revenue;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fecha a lacuna entre apontar uma execução e aceitá-la financeiramente.
 *
 * <p>O RDO comum só registra produção. Esta é a única porta que pode alterar
 * uma linha registrada para um fato financeiro terminal, e ela relê toda a
 * base de cálculo do banco em vez de confiar em preço ou quantidade enviados
 * pelo aparelho.</p>
 */
@Service
public class RdoExecutionDecisionService {

    private static final String SOURCE = "CORTEX_FINANCEIRO";
    private static final int CURRENT_EVENT_SCHEMA_VERSION = 13;

    private final JdbcTemplate jdbc;
    private final RdoQueryService rdoQueryService;
    private final FinancialAccessService financialAccess;
    private final ObraOperabilityGuard operabilityGuard;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;
    private final RevenueOntologyPublisher revenuePublisher;
    private final CortexOperationalMemoryService memory;
    private final RevenueCalculator revenueCalculator = new RevenueCalculator();

    public RdoExecutionDecisionService(
            JdbcTemplate jdbc,
            RdoQueryService rdoQueryService,
            FinancialAccessService financialAccess,
            ObraOperabilityGuard operabilityGuard,
            PrevisaoFinanceiraService previsaoFinanceiraService,
            RevenueOntologyPublisher revenuePublisher,
            CortexOperationalMemoryService memory
    ) {
        this.jdbc = jdbc;
        this.rdoQueryService = rdoQueryService;
        this.financialAccess = financialAccess;
        this.operabilityGuard = operabilityGuard;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
        this.revenuePublisher = revenuePublisher;
        this.memory = memory;
    }

    @Transactional
    public RdoResponse decidir(
            String rdoId,
            String executionId,
            RdoExecutionDecisionRequest request,
            RdoExecutionDecisionAudit audit
    ) {
        String normalizedRdoId = uuid(rdoId, "rdoId");
        String normalizedExecutionId = uuid(executionId, "executionId");
        DecisionInput input = input(request, audit);
        String requestHash = sha256(String.join(
                "|",
                normalizedRdoId,
                normalizedExecutionId,
                input.decision(),
                nullToEmpty(input.justification()),
                Long.toString(input.baseVersion()),
                input.clientMutationId()
        ));

        // A mutation id is global for an actor.  Serializing it before the
        // lookup makes a retry converge even when it targets a different RDO:
        // PostgreSQL otherwise aborts the losing INSERT and no recovery query
        // is legal in that transaction.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { },
                "rdo-execution-decision:"
                        + input.actorId() + ":" + input.clientMutationId()
        );
        ExistingDecision replay = decisionByMutation(
                input.actorId(), input.clientMutationId()
        );
        if (replay != null) {
            if (!replay.matches(
                    normalizedRdoId, normalizedExecutionId, input.decision(),
                    requestHash
            )) {
                throw conflict("RDO_EXECUTION_DECISION_IDEMPOTENCY_MISMATCH");
            }
            return rdoQueryService.buscarPorId(normalizedRdoId);
        }

        // A edição normal do RDO usa esta mesma trava. Sem ela, uma correção
        // de medição poderia passar entre a leitura da execução e a decisão.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { },
                "rdo-revenue:" + normalizedRdoId
        );

        // A concurrent decision on the same RDO can have committed while this
        // request waited for its document lock. Check again before treating an
        // idempotent retry as a terminal-state conflict.
        replay = decisionByMutation(input.actorId(), input.clientMutationId());
        if (replay != null) {
            if (!replay.matches(
                    normalizedRdoId, normalizedExecutionId, input.decision(),
                    requestHash
            )) {
                throw conflict("RDO_EXECUTION_DECISION_IDEMPOTENCY_MISMATCH");
            }
            return rdoQueryService.buscarPorId(normalizedRdoId);
        }

        Execution execution = loadForDecision(
                normalizedRdoId, normalizedExecutionId
        );
        financialAccess.requirePermission(
                execution.worksiteId(), FinancialPermission.FINANCEIRO_APROVAR
        );
        operabilityGuard.requireWritable(execution.worksiteId());
        requireCurrentVersion(execution, input.baseVersion());
        if (hasDecisionForExecution(execution.id())) {
            throw conflict("RDO_EXECUTION_DECISION_ALREADY_FINAL");
        }
        DecisionMode mode = requireDecidable(execution, input.decision());
        if (mode == DecisionMode.NORMAL && "VALIDAR".equals(input.decision())) {
            lockAndRequireActiveService(execution.serviceId());
        }

        Instant decidedAt;
        String revenueEventId = null;
        if (mode == DecisionMode.NORMAL && "VALIDAR".equals(input.decision())) {
            Price price = exactActivePrice(execution);
            String evidenceId = stableUuid(
                    "cortex:revenue-evidence:v1:" + execution.id()
            );
            revenueEventId = stableUuid(
                    "cortex:revenue-event:v1:" + execution.id()
            );
            BigDecimal revenue = revenueCalculator.calculate(
                    "VALIDADA",
                    false,
                    false,
                    execution.quantity(),
                    price.unitPrice()
            );
            decidedAt = accept(
                    execution,
                    price,
                    revenue,
                    evidenceId,
                    revenueEventId
            );
            revenuePublisher.publishAccepted(
                    new RevenueEvidence(
                            evidenceId,
                            revenueEventId,
                            execution.id(),
                            execution.rdoId(),
                            execution.worksiteId(),
                            execution.executionDate(),
                            execution.serviceId(),
                            execution.serviceCode(),
                            execution.serviceName(),
                            price.id(),
                            price.version(),
                            execution.quantity(),
                            execution.unit(),
                            price.currency(),
                            price.unitPrice(),
                            revenue,
                            decidedAt
                    ),
                    input.actorId()
            );
        } else if (mode == DecisionMode.NORMAL) {
            decidedAt = reject(execution);
        } else {
            if (mode == DecisionMode.LEGACY_REVALIDATION) {
                requireLegacyEvidenceVerifiable(execution);
            }
            // Evidência legada é imutável. A decisão só registra uma
            // sobreposição auditada; ela não publica nem recria o fato.
            decidedAt = currentDatabaseInstant();
            revenueEventId = execution.revenueEventId();
        }

        jdbc.update(
                "UPDATE rdo SET versao_linha = versao_linha + 1 WHERE id = ?",
                execution.rdoId()
        );
        String decisionEventId = stableUuid(
                "cortex:rdo-execution-decision:v1:" + execution.id()
        );
        LocalDateTime eventOccurredAt = input.occurredAt() == null
                ? LocalDateTime.ofInstant(decidedAt, ZoneOffset.UTC)
                : input.occurredAt();
        publishDecisionEvent(
                decisionEventId,
                execution,
                input,
                mode,
                revenueEventId,
                eventOccurredAt
        );
        bindDecisionEventBeforeImmutability(
                decisionEventId,
                execution,
                input,
                eventOccurredAt
        );
        persistDecision(
                execution,
                input,
                requestHash,
                decisionEventId,
                mode,
                decidedAt
        );
        previsaoFinanceiraService.recalcularAposMudancaRdo(
                execution.worksiteId(), decisionEventId
        );
        return rdoQueryService.buscarPorId(execution.rdoId());
    }

    private Execution loadForDecision(String rdoId, String executionId) {
        List<Execution> rows = jdbc.query(
                """
                SELECT execution.id,
                       execution.rdo_id,
                       execution.obra_id,
                       rdo.status AS rdo_status,
                       rdo.cancelado_em IS NOT NULL AS rdo_cancelled,
                       COALESCE(sync_state.versao_entidade, 0) AS rdo_version,
                       execution.data_execucao,
                       execution.service_id,
                       service.codigo AS service_code,
                       service.nome AS service_name,
                       service.status AS service_status,
                       execution.quantidade_executada,
                       execution.unidade_medida,
                       execution.status_validacao,
                       execution.retrabalho,
                       execution.producao_rejeitada,
                       execution.cancelada AS execution_cancelled,
                       execution.revenue_evidence_id,
                       execution.revenue_event_id
                FROM execucao_servico_rdo execution
                JOIN rdo ON rdo.id = execution.rdo_id
                LEFT JOIN cortex_estado_entidade sync_state
                  ON sync_state.tipo_entidade = 'RDO'
                 AND sync_state.entidade_id = rdo.id
                LEFT JOIN catalogo_servico service ON service.id = execution.service_id
                WHERE execution.id = ?
                  AND execution.rdo_id = ?
                FOR UPDATE OF execution, rdo
                """,
                (rs, row) -> new Execution(
                        rs.getString("id"),
                        rs.getString("rdo_id"),
                        rs.getString("obra_id"),
                        rs.getString("rdo_status"),
                        rs.getBoolean("rdo_cancelled"),
                        rs.getLong("rdo_version"),
                        rs.getDate("data_execucao").toLocalDate(),
                        rs.getString("service_id"),
                        rs.getString("service_code"),
                        rs.getString("service_name"),
                        rs.getString("service_status"),
                        rs.getBigDecimal("quantidade_executada"),
                        rs.getString("unidade_medida"),
                        rs.getString("status_validacao"),
                        rs.getBoolean("retrabalho"),
                        rs.getBoolean("producao_rejeitada"),
                        rs.getBoolean("execution_cancelled"),
                        rs.getString("revenue_evidence_id"),
                        rs.getString("revenue_event_id")
                ),
                executionId,
                rdoId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "RDO_EXECUTION_NOT_FOUND"
            );
        }
        return rows.getFirst();
    }

    private void requireCurrentVersion(Execution execution, long baseVersion) {
        // A resposta de RDO e o envelope canônico usam a versão da projeção
        // operacional, não o contador físico da linha rdo. Usar os dois
        // contadores em caminhos diferentes deixaria uma decisão offline
        // passar no SyncService e falhar aqui (ou vice-versa).
        if (execution.rdoVersion() != baseVersion) {
            throw conflict("RDO_EXECUTION_DECISION_VERSION_CONFLICT");
        }
    }

    private DecisionMode requireDecidable(
            Execution execution,
            String decision
    ) {
        if (execution.rdoCancelled()
                || execution.executionCancelled()
                || !"ENVIADO".equals(execution.rdoStatus())) {
            throw conflict("RDO_EXECUTION_DECISION_RDO_NOT_ACTIVE");
        }
        if ("VALIDADA".equals(execution.validationStatus())) {
            // Uma rejeição de legado também precisa encerrar uma linha
            // corrompida/incompleta. Ela não a torna receita e não altera a
            // evidência física; apenas registra que ela não é utilizável.
            if ("REJEITAR".equals(decision)) {
                return DecisionMode.LEGACY_REJECTION;
            }
            if (execution.revenueEvidenceId() != null
                    && execution.revenueEventId() != null) {
                return DecisionMode.LEGACY_REVALIDATION;
            }
            throw conflict("RDO_EXECUTION_LEGACY_EVIDENCE_INVALID");
        }
        if (execution.rework() || execution.productionRejected()) {
            throw conflict("RDO_EXECUTION_DECISION_INELIGIBLE_PRODUCTION");
        }
        if (execution.quantity() == null || execution.quantity().signum() <= 0) {
            throw conflict("RDO_EXECUTION_DECISION_QUANTITY_REQUIRED");
        }
        if (execution.revenueEvidenceId() != null
                || execution.revenueEventId() != null
                || !"REGISTRADA".equals(execution.validationStatus())) {
            throw conflict("RDO_EXECUTION_DECISION_ALREADY_FINAL");
        }
        if (execution.serviceId() == null
                || !"ACTIVE".equals(execution.serviceStatus())) {
            throw conflict("RDO_EXECUTION_DECISION_SERVICE_INVALID");
        }
        if (execution.unit() == null || execution.unit().isBlank()) {
            throw conflict("RDO_EXECUTION_DECISION_UNIT_REQUIRED");
        }
        return DecisionMode.NORMAL;
    }

    private void requireLegacyEvidenceVerifiable(Execution execution) {
        Integer matches = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM execucao_servico_rdo legacy
                JOIN obra worksite
                  ON worksite.id = legacy.obra_id
                 AND worksite.arquivado_em IS NULL
                JOIN rdo
                  ON rdo.id = legacy.rdo_id
                 AND rdo.obra_id = legacy.obra_id
                 AND rdo.status = 'ENVIADO'
                 AND rdo.cancelado_em IS NULL
                JOIN service_price_version price
                  ON price.id = legacy.price_version_id
                 AND price.obra_id = legacy.obra_id
                 AND price.service_id = legacy.service_id
                 AND price.unidade = legacy.unidade_medida
                 AND price.moeda = legacy.currency
                 AND price.valor_unitario = legacy.unit_price_snapshot
                 AND price.vigencia_inicio <= legacy.data_execucao
                 AND (
                     cortex_price_effective_valid_to(price.id) IS NULL
                     OR cortex_price_effective_valid_to(price.id)
                         >= legacy.data_execucao
                 )
                WHERE legacy.id = ?
                  AND legacy.rdo_id = ?
                  AND legacy.obra_id = ?
                  AND legacy.status_validacao = 'VALIDADA'
                  AND legacy.revenue_coverage_code = 'ACCEPTED_EXACT'
                  AND legacy.revenue_evidence_id IS NOT NULL
                  AND legacy.revenue_event_id IS NOT NULL
                  AND legacy.accepted_at IS NOT NULL
                  AND legacy.cancelada = FALSE
                  AND legacy.retrabalho = FALSE
                  AND legacy.producao_rejeitada = FALSE
                  AND legacy.quantidade_executada > 0
                  AND cortex_revenue_event_matches_v58(legacy)
                """,
                Integer.class,
                execution.id(), execution.rdoId(), execution.worksiteId()
        );
        if (matches == null || matches != 1) {
            throw conflict("RDO_EXECUTION_LEGACY_EVIDENCE_INVALID");
        }
    }

    private boolean hasDecisionForExecution(String executionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM rdo_execucao_decisao WHERE execution_id = ?",
                Integer.class,
                executionId
        );
        return count != null && count > 0;
    }

    private Price exactActivePrice(Execution execution) {
        List<Price> prices = jdbc.query(
                """
                SELECT id, versao, valor_unitario, moeda
                FROM service_price_version
                WHERE obra_id = ?
                  AND service_id = ?
                  AND unidade = ?
                  AND moeda = 'BRL'
                  AND vigencia_inicio <= ?
                  AND (
                      cortex_price_effective_valid_to(id) IS NULL
                      OR cortex_price_effective_valid_to(id) >= ?
                  )
                ORDER BY id
                """,
                (rs, row) -> new Price(
                        rs.getString("id"),
                        rs.getInt("versao"),
                        rs.getBigDecimal("valor_unitario"),
                        rs.getString("moeda")
                ),
                execution.worksiteId(),
                execution.serviceId(),
                execution.unit().strip().toUpperCase(Locale.ROOT),
                execution.executionDate(),
                execution.executionDate()
        );
        if (prices.isEmpty()) {
            throw conflict("RDO_REVENUE_PRICE_REQUIRED");
        }
        if (prices.size() != 1) {
            throw conflict("RDO_REVENUE_PRICE_AMBIGUOUS");
        }
        return prices.getFirst();
    }

    /**
     * Serializa a aprovação contra a exclusão do catálogo. A leitura da
     * execução traz o status para dar um erro útil, mas não pode decidir sozinha:
     * entre aquela leitura e a atualização da receita o serviço pode ter sido
     * excluído por outra transação.
     */
    private void lockAndRequireActiveService(String serviceId) {
        String status = jdbc.query(
                "SELECT status FROM catalogo_servico WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString("status") : null,
                serviceId
        );
        if (!"ACTIVE".equals(status)) {
            throw conflict("RDO_EXECUTION_DECISION_SERVICE_INVALID");
        }
    }

    private Instant accept(
            Execution execution,
            Price price,
            BigDecimal revenue,
            String evidenceId,
            String revenueEventId
    ) {
        List<Instant> accepted = jdbc.query(
                """
                UPDATE execucao_servico_rdo
                SET status_validacao = 'VALIDADA',
                    estado_receita = 'RECEITA_MEDIDA',
                    price_version_id = ?,
                    unit_price_snapshot = ?,
                    currency = ?,
                    revenue_amount = ?,
                    revenue_coverage_code = 'ACCEPTED_EXACT',
                    revenue_evidence_id = ?,
                    revenue_event_id = ?,
                    accepted_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                  AND rdo_id = ?
                  AND status_validacao = 'REGISTRADA'
                  AND revenue_evidence_id IS NULL
                  AND EXISTS (
                      SELECT 1
                      FROM catalogo_servico service
                      WHERE service.id = ?
                        AND service.status = 'ACTIVE'
                  )
                RETURNING accepted_at
                """,
                (rs, row) -> rs.getTimestamp("accepted_at").toInstant(),
                price.id(),
                price.unitPrice(),
                price.currency(),
                revenue,
                evidenceId,
                revenueEventId,
                execution.id(),
                execution.rdoId(),
                execution.serviceId()
        );
        if (accepted.size() != 1) {
            throw conflict("RDO_EXECUTION_DECISION_ALREADY_FINAL");
        }
        return accepted.getFirst();
    }

    private Instant reject(Execution execution) {
        Instant decidedAt = Instant.now();
        int changed = jdbc.update(
                """
                UPDATE execucao_servico_rdo
                SET status_validacao = 'REJEITADA',
                    estado_receita = 'PRODUCAO_REGISTRADA',
                    price_version_id = NULL,
                    unit_price_snapshot = NULL,
                    currency = NULL,
                    revenue_amount = 0,
                    revenue_coverage_code = 'UNPRICED_REJECTED',
                    revenue_evidence_id = NULL,
                    revenue_event_id = NULL,
                    accepted_at = NULL
                WHERE id = ?
                  AND rdo_id = ?
                  AND status_validacao = 'REGISTRADA'
                  AND revenue_evidence_id IS NULL
                """,
                execution.id(), execution.rdoId()
        );
        if (changed != 1) {
            throw conflict("RDO_EXECUTION_DECISION_ALREADY_FINAL");
        }
        return decidedAt;
    }

    private Instant currentDatabaseInstant() {
        return jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP(6)",
                (rs, row) -> rs.getTimestamp(1).toInstant()
        );
    }

    private void publishDecisionEvent(
            String eventId,
            Execution execution,
            DecisionInput input,
            DecisionMode mode,
            String revenueEventId,
            LocalDateTime occurredAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", CURRENT_EVENT_SCHEMA_VERSION);
        payload.put("rdoId", execution.rdoId());
        payload.put("obraId", execution.worksiteId());
        payload.put("executionId", execution.id());
        payload.put("decisao", input.decision());
        payload.put("clientMutationId", input.clientMutationId());
        payload.put("mode", mode.name());
        if (input.justification() != null) {
            payload.put("justificativa", input.justification());
        }
        if (revenueEventId != null) {
            payload.put("revenueEventId", revenueEventId);
        }
        if (mode == DecisionMode.LEGACY_REJECTION
                && execution.revenueEvidenceId() != null) {
            payload.put(
                    "blockedRevenueEvidenceId", execution.revenueEvidenceId()
            );
        }
        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("statusValidacao", execution.validationStatus());
        beforeState.put("reviewMode", "NONE");
        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put(
                "statusValidacao",
                mode == DecisionMode.NORMAL
                        ? ("VALIDAR".equals(input.decision())
                                ? "VALIDADA" : "REJEITADA")
                        : execution.validationStatus()
        );
        afterState.put("financialDecision", input.decision());
        afterState.put("reviewMode", mode.name());
        memory.registrarEventoAuditado(
                eventId,
                "RDO",
                execution.rdoId(),
                "VALIDAR".equals(input.decision())
                        ? "RDO_EXECUCAO_VALIDADA"
                        : "RDO_EXECUCAO_REJEITADA",
                SOURCE,
                execution.worksiteId(),
                execution.rdoId(),
                null,
                List.of(
                        Map.of("tipo", "RDO", "id", execution.rdoId()),
                        Map.of("tipo", "WORKSITE", "id", execution.worksiteId()),
                        Map.of("tipo", "RDO_EXECUTION", "id", execution.id())
                ),
                input.origin(),
                "SYNCED",
                occurredAt,
                occurredAt,
                CURRENT_EVENT_SCHEMA_VERSION,
                payload,
                input.actorId(),
                input.deviceId(),
                input.correlationId(),
                input.causationId(),
                beforeState,
                afterState,
                "SUCESSO",
                null
        );
    }

    /**
     * O SyncService normalmente anexa o rastro v13 ao evento do domínio depois
     * que o handler retorna. Uma decisão financeira, porém, aponta para esse
     * evento e o sela no banco. Portanto o vínculo acontece aqui, antes do
     * INSERT da decisão: o recibo de sync só registra o commit já auditado.
     */
    private void bindDecisionEventBeforeImmutability(
            String eventId,
            Execution execution,
            DecisionInput input,
            LocalDateTime occurredAt
    ) {
        Long entityVersion = jdbc.queryForObject(
                """
                SELECT versao_entidade
                FROM cortex_estado_entidade
                WHERE tipo_entidade = 'RDO'
                  AND entidade_id = ?
                """,
                Long.class,
                execution.rdoId()
        );
        if (entityVersion == null) {
            throw new IllegalStateException(
                    "Evento da decisão financeira foi criado sem versão canônica do RDO."
            );
        }
        int updated = jdbc.update(
                """
                UPDATE cortex_evento_operacional
                SET usuario_id = ?,
                    dispositivo_id = ?,
                    correlacao_id = ?,
                    causacao_id = ?,
                    resultado = 'SUCESSO',
                    erro_categoria = NULL,
                    versao_entidade = ?,
                    schema_version = ?,
                    client_mutation_id = ?,
                    evento_cliente_id = ?,
                    ocorrido_em = ?
                WHERE id = ?
                """,
                input.actorId(),
                input.deviceId(),
                input.correlationId(),
                input.causationId(),
                entityVersion,
                CURRENT_EVENT_SCHEMA_VERSION,
                input.clientMutationId(),
                input.clientEventId(),
                occurredAt,
                eventId
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Evento da decisão financeira não pôde receber o rastro canônico."
            );
        }
    }

    private void persistDecision(
            Execution execution,
            DecisionInput input,
            String requestHash,
            String eventId,
            DecisionMode mode,
            Instant decidedAt
    ) {
        jdbc.update(
                """
                INSERT INTO rdo_execucao_decisao (
                    id, execution_id, rdo_id, obra_id, decisao,
                    justificativa, decidido_por, dispositivo_id,
                    correlacao_id, client_mutation_id, request_hash,
                    evento_id, review_mode, decidido_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                execution.id(),
                execution.rdoId(),
                execution.worksiteId(),
                input.decision(),
                input.justification(),
                input.actorId(),
                input.deviceId(),
                input.correlationId(),
                input.clientMutationId(),
                requestHash,
                eventId,
                mode.name(),
                java.sql.Timestamp.from(decidedAt)
        );
    }

    private ExistingDecision decisionByMutation(
            String actorId,
            String clientMutationId
    ) {
        List<ExistingDecision> rows = jdbc.query(
                """
                SELECT execution_id, rdo_id, decisao, request_hash
                FROM rdo_execucao_decisao
                WHERE decidido_por = ?
                  AND client_mutation_id = ?
                """,
                (rs, row) -> new ExistingDecision(
                        rs.getString("execution_id"),
                        rs.getString("rdo_id"),
                        rs.getString("decisao"),
                        rs.getString("request_hash")
                ),
                actorId,
                clientMutationId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private DecisionInput input(
            RdoExecutionDecisionRequest request,
            RdoExecutionDecisionAudit audit
    ) {
        if (request == null || audit == null) {
            throw badRequest("RDO_EXECUTION_DECISION_REQUIRED");
        }
        String decision = text(request.decisao(), "decisao", 20)
                .toUpperCase(Locale.ROOT);
        if (!"VALIDAR".equals(decision) && !"REJEITAR".equals(decision)) {
            throw badRequest("RDO_EXECUTION_DECISION_INVALID");
        }
        if (request.baseVersao() == null || request.baseVersao() < 0) {
            throw badRequest("RDO_EXECUTION_DECISION_BASE_VERSION_REQUIRED");
        }
        String justification = optionalText(request.justificativa(), 2000);
        if ("REJEITAR".equals(decision) && justification == null) {
            throw badRequest("RDO_EXECUTION_DECISION_JUSTIFICATION_REQUIRED");
        }
        return new DecisionInput(
                decision,
                justification,
                request.baseVersao(),
                uuid(request.clientMutationId(), "clientMutationId"),
                uuid(audit.actorId(), "actorId"),
                optionalUuid(audit.deviceId(), "deviceId"),
                optionalText(audit.correlationId(), 120),
                optionalText(audit.origin(), 20) == null
                        ? "ONLINE"
                        : audit.origin().strip().toUpperCase(Locale.ROOT),
                optionalUuid(audit.causationId(), "causationId"),
                optionalUuid(audit.clientEventId(), "clientEventId"),
                audit.occurredAt()
        );
    }

    private String uuid(String raw, String field) {
        try {
            return UUID.fromString(text(raw, field, 120)).toString();
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " inválido.");
        }
    }

    private String optionalUuid(String raw, String field) {
        return raw == null || raw.isBlank() ? null : uuid(raw, field);
    }

    private String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw badRequest(field + " excede o tamanho permitido.");
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw badRequest("justificativa excede o tamanho permitido.");
        }
        return normalized;
    }

    private String stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível gerar hash da decisão financeira.",
                    exception
            );
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private record DecisionInput(
            String decision,
            String justification,
            long baseVersion,
            String clientMutationId,
            String actorId,
            String deviceId,
            String correlationId,
            String origin,
            String causationId,
            String clientEventId,
            LocalDateTime occurredAt
        ) {
    }

    private record ExistingDecision(
            String executionId,
            String rdoId,
            String decision,
            String requestHash
    ) {
        private boolean matches(
                String expectedRdoId,
                String expectedExecutionId,
                String expectedDecision,
                String expectedRequestHash
        ) {
            return rdoId.equals(expectedRdoId)
                    && executionId.equals(expectedExecutionId)
                    && decision.equals(expectedDecision)
                    && requestHash.equals(expectedRequestHash);
        }
    }

    private enum DecisionMode {
        NORMAL,
        LEGACY_REVALIDATION,
        LEGACY_REJECTION
    }

    private record Execution(
            String id,
            String rdoId,
            String worksiteId,
            String rdoStatus,
            boolean rdoCancelled,
            long rdoVersion,
            java.time.LocalDate executionDate,
            String serviceId,
            String serviceCode,
            String serviceName,
            String serviceStatus,
            BigDecimal quantity,
            String unit,
            String validationStatus,
            boolean rework,
            boolean productionRejected,
            boolean executionCancelled,
            String revenueEvidenceId,
            String revenueEventId
    ) {
    }

    private record Price(
            String id,
            int version,
            BigDecimal unitPrice,
            String currency
    ) {
    }
}

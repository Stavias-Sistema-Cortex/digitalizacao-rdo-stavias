package com.projeto.cortex.financeiro.core;

import static com.projeto.cortex.financeiro.core.FinanceDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancePurchaseService {

    private static final String SOLICITATION_SELECT = """
            SELECT s.*, o.nome AS obra_nome,
                   cc.nome AS centro_custo_nome,
                   cat.nome AS categoria_nome,
                   f.razao_social AS fornecedor_nome,
                   sol.nome AS solicitante_nome,
                   resp.nome AS responsavel_nome,
                   st.codigo AS status_codigo,
                   st.nome AS status_nome
            FROM finance_solicitacao_compra s
            JOIN obra o ON o.id = s.obra_id
            JOIN finance_centro_custo cc ON cc.id = s.centro_custo_id
            JOIN finance_categoria cat ON cat.id = s.categoria_id
            LEFT JOIN finance_fornecedor f ON f.id = s.fornecedor_id
            JOIN colaborador sol ON sol.id = s.solicitante_id
            LEFT JOIN colaborador resp ON resp.id = s.responsavel_compra_id
            JOIN finance_status_definicao st ON st.id = s.status_id
            """;

    private static final String PURCHASE_SELECT = """
            SELECT c.*, o.nome AS obra_nome,
                   cc.nome AS centro_custo_nome,
                   cat.nome AS categoria_nome,
                   f.razao_social AS fornecedor_nome,
                   resp.nome AS responsavel_nome,
                   st.codigo AS status_codigo,
                   st.nome AS status_nome
            FROM finance_compra c
            JOIN obra o ON o.id = c.obra_id
            JOIN finance_centro_custo cc ON cc.id = c.centro_custo_id
            JOIN finance_categoria cat ON cat.id = c.categoria_id
            JOIN finance_fornecedor f ON f.id = c.fornecedor_id
            JOIN colaborador resp ON resp.id = c.responsavel_compra_id
            JOIN finance_status_definicao st ON st.id = c.status_id
            """;

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final FinancialAccessService financialAccess;
    private final FinanceOntologyProjector ontology;
    private final ObjectMapper objectMapper;
    private final ObraOperabilityGuard obraOperabilityGuard;

    public FinancePurchaseService(
            JdbcTemplate jdbc,
            CurrentUserService currentUser,
            FinancialAccessService financialAccess,
            FinanceOntologyProjector ontology,
            ObjectMapper objectMapper,
            ObraOperabilityGuard obraOperabilityGuard
    ) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.financialAccess = financialAccess;
        this.ontology = ontology;
        this.objectMapper = objectMapper;
        this.obraOperabilityGuard = obraOperabilityGuard;
    }

    @Transactional
    public SolicitationResponse saveSolicitation(
            SolicitationRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        ValidatedSolicitation input = validate(request);
        String requestHash = mutationPayloadHash("SAVE_SOLICITATION", request);
        financialAccess.requirePermission(
                input.obraId(), FinancialPermission.FINANCEIRO_OPERAR
        );
        MutationReceipt replayReceipt = mutationReceipt(
                "SOLICITACAO",
                audit.actorId(),
                input.clientMutationId()
        );
        if (replayReceipt != null) {
            requireMatchingMutation(
                    replayReceipt,
                    input.id(),
                    input.explicitId(),
                    "SAVE_SOLICITATION",
                    requestHash
            );
            return solicitation(replayReceipt.entityId());
        }
        String existingByCreateMutation = jdbc.query(
                """
                SELECT id FROM finance_solicitacao_compra
                WHERE criado_por = ? AND client_mutation_id = ? LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                audit.actorId(),
                input.clientMutationId()
        );
        if (existingByCreateMutation != null) {
            SolicitationResponse existing = solicitation(existingByCreateMutation);
            if (!sameSolicitation(existing, input)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "clientMutationId já foi usado com outro conteúdo."
                );
            }
            return existing;
        }
        obraOperabilityGuard.requireWritable(input.obraId());

        String id = input.id();
        SolicitationResponse previous = findSolicitation(id);
        if (previous != null && !previous.obraId().equals(input.obraId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A solicitação pertence a outra obra."
            );
        }
        if (previous == null) {
            jdbc.update(
                    """
                    INSERT INTO finance_solicitacao_compra (
                        id, client_mutation_id, obra_id, centro_custo_id,
                        categoria_id, fornecedor_id, solicitante_id,
                        responsavel_compra_id, status_id, titulo, descricao,
                        prioridade, moeda, valor_previsto, valor_aprovado,
                        valor_contratado, valor_pago, necessario_em, criado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              ?, ?, ?, ?)
                    """,
                    id, input.clientMutationId(), input.obraId(),
                    input.centroCustoId(), input.categoriaId(),
                    input.fornecedorId(), input.solicitanteId(),
                    input.responsavelCompraId(), input.statusId(),
                    input.titulo(), input.descricao(), input.prioridade(),
                    input.moeda(), input.valorPrevisto(), input.valorAprovado(),
                    input.valorContratado(), input.valorPago(),
                    input.necessarioEm(), audit.actorId()
            );
            saveSolicitationItems(id, input.itens(), audit.actorId());
        } else {
            if (request.baseVersao() == null
                    || previous.versao() != request.baseVersao()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A solicitação possui uma versão mais recente."
                );
            }
            int changed = jdbc.update(
                    """
                    UPDATE finance_solicitacao_compra
                    SET centro_custo_id = ?, categoria_id = ?, fornecedor_id = ?,
                        solicitante_id = ?, responsavel_compra_id = ?,
                        titulo = ?, descricao = ?, prioridade = ?, moeda = ?,
                        valor_previsto = ?, valor_aprovado = ?,
                        valor_contratado = ?, valor_pago = ?, necessario_em = ?,
                        atualizado_por = ?, versao_linha = versao_linha + 1
                    WHERE id = ? AND obra_id = ? AND versao_linha = ?
                      AND arquivado_em IS NULL
                    """,
                    input.centroCustoId(), input.categoriaId(),
                    input.fornecedorId(), input.solicitanteId(),
                    input.responsavelCompraId(), input.titulo(),
                    input.descricao(), input.prioridade(), input.moeda(),
                    input.valorPrevisto(), input.valorAprovado(),
                    input.valorContratado(), input.valorPago(),
                    input.necessarioEm(), audit.actorId(), id, input.obraId(),
                    request.baseVersao()
            );
            if (changed != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A solicitação mudou durante a edição."
                );
            }
            saveSolicitationItems(id, input.itens(), audit.actorId());
        }
        SolicitationResponse response = solicitation(id);
        recordHistory(
                "SOLICITACAO", id, input.obraId(),
                previous == null ? null : previous.statusId(),
                response.statusId(), audit, input.clientMutationId(),
                previous == null ? Map.of() : solicitationState(previous),
                solicitationState(response),
                "SAVE_SOLICITATION",
                requestHash
        );
        ontology.success(
                "SOLICITACAO_COMPRA", id, response.titulo(),
                previous == null
                        ? "SOLICITACAO_COMPRA_CRIADA"
                        : "SOLICITACAO_COMPRA_ATUALIZADA",
                response.obraId(), audit,
                previous == null ? Map.of() : solicitationState(previous),
                solicitationState(response),
                related(
                        "FORNECEDOR", response.fornecedorId(),
                        "CENTRO_CUSTO", response.centroCustoId(),
                        "CATEGORIA_FINANCEIRA", response.categoriaId(),
                        "RESPONSAVEL", response.responsavelCompraId()
                )
        );
        return response;
    }

    public List<SolicitationResponse> listSolicitations(FinanceFilter filter) {
        FinanceFilter value = filter.normalized();
        StringBuilder sql = new StringBuilder(SOLICITATION_SELECT)
                .append(" WHERE s.arquivado_em IS NULL AND s.obra_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(value.obraId());
        appendFilter(sql, args, "s.criado_em", value.from(), value.to());
        appendEquals(sql, args, "s.responsavel_compra_id", value.responsavelId());
        appendEquals(sql, args, "s.fornecedor_id", value.fornecedorId());
        appendEquals(sql, args, "s.status_id", value.statusId());
        appendEquals(sql, args, "s.categoria_id", value.categoriaId());
        appendEquals(sql, args, "s.centro_custo_id", value.centroCustoId());
        appendEquals(sql, args, "s.prioridade", value.prioridade());
        if (value.query() != null) {
            sql.append(" AND (s.titulo LIKE CONCAT('%', ?, '%') ")
                    .append("OR COALESCE(s.descricao, '') LIKE CONCAT('%', ?, '%')) ");
            args.add(value.query());
            args.add(value.query());
        }
        sql.append(" ORDER BY s.criado_em DESC, s.id DESC LIMIT ? OFFSET ?");
        args.add(value.limit());
        args.add(value.offset());
        return jdbc.query(sql.toString(), this::mapSolicitation, args.toArray());
    }

    @Transactional
    public PurchaseResponse savePurchase(
            PurchaseRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        ValidatedPurchase input = validate(request);
        String requestHash = mutationPayloadHash("SAVE_PURCHASE", request);
        financialAccess.requirePermission(
                input.obraId(), FinancialPermission.FINANCEIRO_OPERAR
        );
        MutationReceipt replayReceipt = mutationReceipt(
                "COMPRA", audit.actorId(), input.clientMutationId()
        );
        if (replayReceipt != null) {
            requireMatchingMutation(
                    replayReceipt,
                    input.id(),
                    input.explicitId(),
                    "SAVE_PURCHASE",
                    requestHash
            );
            return purchase(replayReceipt.entityId());
        }
        String existingByCreateMutation = jdbc.query(
                """
                SELECT id FROM finance_compra
                WHERE criado_por = ? AND client_mutation_id = ? LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                audit.actorId(), input.clientMutationId()
        );
        if (existingByCreateMutation != null) {
            PurchaseResponse existing = purchase(existingByCreateMutation);
            if (!samePurchase(existing, input)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "clientMutationId já foi usado com outro conteúdo."
                );
            }
            return existing;
        }
        obraOperabilityGuard.requireWritable(input.obraId());
        String id = input.id();
        PurchaseResponse previous = findPurchase(id);
        if (previous != null && !previous.obraId().equals(input.obraId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A compra pertence a outra obra."
            );
        }
        if (previous == null) {
            jdbc.update(
                    """
                    INSERT INTO finance_compra (
                        id, client_mutation_id, solicitacao_id, obra_id,
                        centro_custo_id, categoria_id, fornecedor_id,
                        responsavel_compra_id, status_id, numero_pedido,
                        descricao, prioridade, moeda, valor_previsto,
                        valor_aprovado, valor_contratado, valor_pago,
                        pedido_emitido_em, entrega_prevista_em, criado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              ?, ?, ?, ?, ?)
                    """,
                    id, input.clientMutationId(), input.solicitacaoId(),
                    input.obraId(), input.centroCustoId(), input.categoriaId(),
                    input.fornecedorId(), input.responsavelCompraId(),
                    input.statusId(), input.numeroPedido(), input.descricao(),
                    input.prioridade(), input.moeda(), input.valorPrevisto(),
                    input.valorAprovado(), input.valorContratado(),
                    input.valorPago(), input.pedidoEmitidoEm(),
                    input.entregaPrevistaEm(), audit.actorId()
            );
            savePurchaseItems(id, input.itens(), audit.actorId());
        } else {
            if (request.baseVersao() == null
                    || previous.versao() != request.baseVersao()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A compra possui uma versão mais recente."
                );
            }
            int changed = jdbc.update(
                    """
                    UPDATE finance_compra
                    SET solicitacao_id = ?, centro_custo_id = ?,
                        categoria_id = ?, fornecedor_id = ?,
                        responsavel_compra_id = ?, numero_pedido = ?,
                        descricao = ?, prioridade = ?, moeda = ?,
                        valor_previsto = ?, valor_aprovado = ?,
                        valor_contratado = ?, valor_pago = ?,
                        pedido_emitido_em = ?, entrega_prevista_em = ?,
                        atualizado_por = ?, versao_linha = versao_linha + 1
                    WHERE id = ? AND obra_id = ? AND versao_linha = ?
                      AND arquivado_em IS NULL
                    """,
                    input.solicitacaoId(), input.centroCustoId(),
                    input.categoriaId(), input.fornecedorId(),
                    input.responsavelCompraId(), input.numeroPedido(),
                    input.descricao(), input.prioridade(), input.moeda(),
                    input.valorPrevisto(), input.valorAprovado(),
                    input.valorContratado(), input.valorPago(),
                    input.pedidoEmitidoEm(), input.entregaPrevistaEm(),
                    audit.actorId(), id, input.obraId(), request.baseVersao()
            );
            if (changed != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A compra mudou durante a edição."
                );
            }
            savePurchaseItems(id, input.itens(), audit.actorId());
        }
        PurchaseResponse response = purchase(id);
        recordHistory(
                "COMPRA", id, response.obraId(),
                previous == null ? null : previous.statusId(),
                response.statusId(), audit, input.clientMutationId(),
                previous == null ? Map.of() : purchaseState(previous),
                purchaseState(response),
                "SAVE_PURCHASE",
                requestHash
        );
        ontology.success(
                "COMPRA", id, response.numeroPedido() == null
                        ? response.descricao() : response.numeroPedido(),
                previous == null ? "COMPRA_CRIADA" : "COMPRA_ATUALIZADA",
                response.obraId(), audit,
                previous == null ? Map.of() : purchaseState(previous),
                purchaseState(response),
                related(
                        "SOLICITACAO_COMPRA", response.solicitacaoId(),
                        "FORNECEDOR", response.fornecedorId(),
                        "CENTRO_CUSTO", response.centroCustoId(),
                        "CATEGORIA_FINANCEIRA", response.categoriaId(),
                        "RESPONSAVEL", response.responsavelCompraId()
                )
        );
        return response;
    }

    public List<PurchaseResponse> listPurchases(FinanceFilter filter) {
        FinanceFilter value = filter.normalized();
        StringBuilder sql = new StringBuilder(PURCHASE_SELECT)
                .append(" WHERE c.arquivado_em IS NULL AND c.obra_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(value.obraId());
        appendFilter(sql, args, "c.criado_em", value.from(), value.to());
        appendEquals(sql, args, "c.responsavel_compra_id", value.responsavelId());
        appendEquals(sql, args, "c.fornecedor_id", value.fornecedorId());
        appendEquals(sql, args, "c.status_id", value.statusId());
        appendEquals(sql, args, "c.categoria_id", value.categoriaId());
        appendEquals(sql, args, "c.centro_custo_id", value.centroCustoId());
        appendEquals(sql, args, "c.prioridade", value.prioridade());
        if (value.query() != null) {
            sql.append(" AND (c.descricao LIKE CONCAT('%', ?, '%') ")
                    .append("OR COALESCE(c.numero_pedido, '') LIKE CONCAT('%', ?, '%')) ");
            args.add(value.query());
            args.add(value.query());
        }
        sql.append(" ORDER BY c.criado_em DESC, c.id DESC LIMIT ? OFFSET ?");
        args.add(value.limit());
        args.add(value.offset());
        return jdbc.query(sql.toString(), this::mapPurchase, args.toArray());
    }

    @Transactional
    public StatusChangeResponse changePurchaseStatus(
            String purchaseId,
            StatusChangeRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        if (request == null || request.baseVersao() == null) {
            throw FinanceValidation.badRequest(
                    "Status, clientMutationId e baseVersao são obrigatórios."
            );
        }
        String id = FinanceValidation.uuid(purchaseId, "compraId");
        String mutationId = FinanceValidation.mutationId(request.clientMutationId());
        String target = FinanceValidation.uuid(request.statusId(), "statusId");
        String requestHash = mutationPayloadHash(
                "CHANGE_PURCHASE_STATUS",
                Map.of(
                        "purchaseId", id,
                        "statusId", target,
                        "baseVersao", request.baseVersao(),
                        "clientMutationId", mutationId
                )
        );
        PurchaseResponse purchase = purchase(id);
        financialAccess.requirePermission(
                purchase.obraId(), FinancialPermission.FINANCEIRO_OPERAR
        );
        MutationReceipt replayReceipt = mutationReceipt(
                "COMPRA", audit.actorId(), mutationId
        );
        if (replayReceipt != null) {
            requireMatchingMutation(
                    replayReceipt,
                    id,
                    true,
                    "CHANGE_PURCHASE_STATUS",
                    requestHash
            );
            return statusResponse(purchase, true, null, null);
        }
        obraOperabilityGuard.requireWritable(purchase.obraId());
        Transition transition = transition(
                purchase.obraId(), "COMPRA", purchase.statusId(), target
        );
        if (transition.approvalRequired()) {
            Approval approval = approvalFor(id, target);
            if (approval == null) {
                approval = createApproval(purchase, target, audit);
                return statusResponse(
                        purchase, false, approval.id(), approval.status()
                );
            }
            if (!"APROVADA".equals(approval.status())) {
                return statusResponse(
                        purchase, false, approval.id(), approval.status()
                );
            }
        }
        int changed = jdbc.update(
                """
                UPDATE finance_compra
                SET status_id = ?, atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ? AND obra_id = ? AND versao_linha = ?
                  AND arquivado_em IS NULL
                """,
                target, audit.actorId(), id, purchase.obraId(),
                request.baseVersao()
        );
        if (changed != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A compra possui uma versão mais recente."
            );
        }
        PurchaseResponse updated = purchase(id);
        recordHistory(
                "COMPRA", id, updated.obraId(), purchase.statusId(), target,
                audit, mutationId, purchaseState(purchase), purchaseState(updated),
                "CHANGE_PURCHASE_STATUS", requestHash
        );
        ontology.success(
                "COMPRA", id, updated.descricao(),
                "COMPRA_STATUS_ALTERADO", updated.obraId(), audit,
                purchaseState(purchase), purchaseState(updated), Map.of()
        );
        return statusResponse(updated, true, null, null);
    }

    @Transactional
    public ApprovalDecisionResponse decidePurchase(
            String purchaseId,
            ApprovalDecisionRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        PurchaseResponse purchase = purchase(
                FinanceValidation.uuid(purchaseId, "compraId")
        );
        financialAccess.requirePermission(
                purchase.obraId(), FinancialPermission.FINANCEIRO_APROVAR
        );
        if (request == null) {
            throw FinanceValidation.badRequest("Decisão é obrigatória.");
        }
        String decision = FinanceValidation.requiredText(
                request.decisao(), "decisao", 20
        ).toUpperCase(Locale.ROOT);
        if (!decision.equals("APROVAR") && !decision.equals("REJEITAR")) {
            throw FinanceValidation.badRequest("Decisão inválida.");
        }
        String mutationId = FinanceValidation.mutationId(request.clientMutationId());
        String justification = FinanceValidation.optionalText(
                request.justificativa(), "justificativa", 2000
        );
        String requestHash = mutationPayloadHash(
                "DECIDE_PURCHASE",
                state(
                        "purchaseId", purchase.id(),
                        "decision", decision,
                        "justification", justification,
                        "clientMutationId", mutationId
                )
        );
        MutationReceipt replayReceipt = mutationReceipt(
                "COMPRA", audit.actorId(), mutationId
        );
        if (replayReceipt != null) {
            requireMatchingMutation(
                    replayReceipt,
                    purchase.id(),
                    true,
                    "DECIDE_PURCHASE",
                    requestHash
            );
            ExistingDecision boundReplay = decisionByMutation(
                    audit.actorId(), mutationId
            );
            if (boundReplay == null) {
                throw new IllegalStateException(
                        "Recibo de decisão não possui decisão financeira vinculada."
                );
            }
            return boundReplay.response();
        }
        ExistingDecision replay = decisionByMutation(
                audit.actorId(), mutationId
        );
        if (replay != null) {
            if (!purchase.id().equals(replay.purchaseId())
                    || !decision.equals(replay.decision())
                    || !Objects.equals(justification, replay.justification())) {
                throw mutationConflict();
            }
            recordDecisionMutation(
                    purchase,
                    audit,
                    mutationId,
                    decision,
                    justification,
                    requestHash,
                    replay.response().statusAprovacao()
            );
            return replay.response();
        }
        obraOperabilityGuard.requireWritable(purchase.obraId());
        Approval approval = pendingApproval(purchase.id());
        if (approval == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A compra não possui aprovação pendente."
            );
        }
        ApprovalStage stage = pendingStage(approval.id());
        if (stage == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A aprovação não possui etapa pendente."
            );
        }
        if ("COLABORADOR".equals(stage.approverType())
                && !audit.actorId().equals(stage.approverId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Esta etapa exige outro aprovador."
            );
        }
        LocalDateTime decidedAt = LocalDateTime.now(ZoneOffset.UTC);
        try {
            jdbc.update(
                    """
                    INSERT INTO finance_aprovacao_decisao (
                        id, aprovacao_etapa_id, decisao, justificativa,
                        decidido_por, client_mutation_id, dispositivo_id,
                        correlacao_id, decidido_em
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), stage.id(), decision,
                    justification,
                    audit.actorId(), mutationId, audit.deviceId(),
                    audit.correlationId(), decidedAt
            );
        } catch (DuplicateKeyException duplicate) {
            ExistingDecision concurrentReplay = decisionByMutation(
                    audit.actorId(), mutationId
            );
            if (concurrentReplay != null
                    && purchase.id().equals(concurrentReplay.purchaseId())
                    && decision.equals(concurrentReplay.decision())
                    && Objects.equals(
                            justification,
                            concurrentReplay.justification()
                    )) {
                recordDecisionMutation(
                        purchase,
                        audit,
                        mutationId,
                        decision,
                        justification,
                        requestHash,
                        concurrentReplay.response().statusAprovacao()
                );
                return concurrentReplay.response();
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Este aprovador já registrou uma decisão para a etapa."
            );
        }
        if (decision.equals("REJEITAR")) {
            jdbc.update(
                    "UPDATE finance_aprovacao_etapa SET status = 'REJEITADA', concluida_em = ? WHERE id = ?",
                    decidedAt, stage.id()
            );
            jdbc.update(
                    "UPDATE finance_aprovacao SET status = 'REJEITADA', concluido_em = ? WHERE id = ?",
                    decidedAt, approval.id()
            );
        } else {
            Integer approvals = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM finance_aprovacao_decisao
                    WHERE aprovacao_etapa_id = ? AND decisao = 'APROVAR'
                    """,
                    Integer.class,
                    stage.id()
            );
            if (approvals != null && approvals >= stage.requiredApprovals()) {
                jdbc.update(
                        "UPDATE finance_aprovacao_etapa SET status = 'APROVADA', concluida_em = ? WHERE id = ?",
                        decidedAt, stage.id()
                );
                ApprovalStage next = stageAfter(approval.id(), stage.order());
                if (next == null) {
                    jdbc.update(
                            "UPDATE finance_aprovacao SET status = 'APROVADA', concluido_em = ? WHERE id = ?",
                            decidedAt, approval.id()
                    );
                }
            }
        }
        Approval refreshed = approval(approval.id());
        ApprovalStage refreshedStage = stage(stage.id());
        ontology.success(
                "APROVACAO_FINANCEIRA", approval.id(),
                "Aprovação da compra " + purchase.id(),
                "DECISAO_APROVACAO_REGISTRADA", purchase.obraId(), audit,
                Map.of("status", approval.status()),
                Map.of(
                        "status", refreshed.status(),
                        "decisao", decision,
                        "etapa", refreshedStage.order()
                ),
                Map.of("COMPRA", purchase.id(), "RESPONSAVEL", audit.actorId())
        );
        ExistingDecision persisted = decisionByMutation(
                audit.actorId(), mutationId
        );
        if (persisted == null) {
            throw new IllegalStateException(
                    "Decisão financeira persistida não foi encontrada."
            );
        }
        recordDecisionMutation(
                purchase,
                audit,
                mutationId,
                decision,
                justification,
                requestHash,
                persisted.response().statusAprovacao()
        );
        return persisted.response();
    }

    private void recordDecisionMutation(
            PurchaseResponse purchase,
            FinanceAuditContext audit,
            String mutationId,
            String decision,
            String justification,
            String requestHash,
            String approvalStatus
    ) {
        recordHistory(
                "COMPRA",
                purchase.id(),
                purchase.obraId(),
                purchase.statusId(),
                purchase.statusId(),
                audit,
                mutationId,
                state("approvalStatus", "PENDENTE"),
                state(
                        "approvalStatus", approvalStatus,
                        "decision", decision,
                        "justification", justification
                ),
                "DECIDE_PURCHASE",
                requestHash
        );
    }

    @Transactional
    public void archiveSolicitation(
            String id,
            long baseVersion,
            String clientMutationId,
            FinanceAuditContext audit
    ) {
        archive(
                "finance_solicitacao_compra", "SOLICITACAO_COMPRA",
                "SOLICITACAO", id, baseVersion, clientMutationId, audit
        );
    }

    @Transactional
    public void archivePurchase(
            String id,
            long baseVersion,
            String clientMutationId,
            FinanceAuditContext audit
    ) {
        archive(
                "finance_compra", "COMPRA", "COMPRA",
                id, baseVersion, clientMutationId, audit
        );
    }

    private void archive(
            String table,
            String entityType,
            String aggregate,
            String rawId,
            long baseVersion,
            String clientMutationId,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String id = FinanceValidation.uuid(rawId, "id");
        String mutation = FinanceValidation.mutationId(clientMutationId);
        String operation = "ARCHIVE_" + aggregate;
        String requestHash = mutationPayloadHash(
                operation,
                Map.of(
                        "entityId", id,
                        "baseVersao", baseVersion,
                        "clientMutationId", mutation
                )
        );
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT obra_id, status_id, versao_linha FROM "
                            + table + " WHERE id = ?",
                    id
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Registro financeiro não encontrado."
            );
        }
        String worksite = row.get("obra_id").toString();
        financialAccess.requirePermission(
                worksite, FinancialPermission.FINANCEIRO_OPERAR
        );
        MutationReceipt replayReceipt = mutationReceipt(
                aggregate, audit.actorId(), mutation
        );
        if (replayReceipt != null) {
            requireMatchingMutation(
                    replayReceipt,
                    id,
                    true,
                    operation,
                    requestHash
            );
            return;
        }
        int changed = jdbc.update(
                "UPDATE " + table + " SET arquivado_em = CURRENT_TIMESTAMP(6), arquivado_por = ?, versao_linha = versao_linha + 1 WHERE id = ? AND obra_id = ? AND versao_linha = ? AND arquivado_em IS NULL",
                audit.actorId(), id, worksite, baseVersion
        );
        if (changed != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O registro possui uma versão mais recente."
            );
        }
        String statusId = row.get("status_id").toString();
        recordHistory(
                aggregate, id, worksite, statusId, statusId,
                audit, mutation, row, Map.of("arquivado", true),
                operation, requestHash
        );
        ontology.success(
                entityType, id, entityType + " " + id,
                entityType + "_ARQUIVADA", worksite, audit,
                row, Map.of("arquivado", true), Map.of()
        );
    }

    private Approval createApproval(
            PurchaseResponse purchase,
            String targetStatusId,
            FinanceAuditContext audit
    ) {
        ApprovalRule rule = matchingRule(purchase);
        if (rule == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Nenhuma regra de aprovação ativa corresponde a esta compra."
            );
        }
        String approvalId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_aprovacao (
                    id, agregado_tipo, compra_id, obra_id, regra_id,
                    status_origem_id, status_destino_id, moeda,
                    valor_submetido, criado_por
                ) VALUES (?, 'COMPRA', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                approvalId, purchase.id(), purchase.obraId(), rule.id(),
                purchase.statusId(), targetStatusId, purchase.moeda(),
                purchase.valorContratado(), audit.actorId()
        );
        List<Map<String, Object>> steps = jdbc.queryForList(
                """
                SELECT * FROM finance_regra_aprovacao_etapa
                WHERE regra_id = ? ORDER BY ordem
                """,
                rule.id()
        );
        if (steps.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A regra de aprovação não possui etapas."
            );
        }
        for (Map<String, Object> step : steps) {
            jdbc.update(
                    """
                    INSERT INTO finance_aprovacao_etapa (
                        id, aprovacao_id, regra_etapa_id, ordem,
                        aprovador_tipo, aprovador_colaborador_id,
                        permissao_requerida, aprovacoes_necessarias
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), approvalId, step.get("id"),
                    step.get("ordem"), step.get("aprovador_tipo"),
                    step.get("aprovador_colaborador_id"),
                    step.get("permissao_requerida"),
                    step.get("aprovacoes_necessarias")
            );
        }
        ontology.success(
                "APROVACAO_FINANCEIRA", approvalId,
                "Aprovação da compra " + purchase.id(),
                "APROVACAO_FINANCEIRA_CRIADA", purchase.obraId(), audit,
                Map.of(), Map.of(
                        "regraId", rule.id(),
                        "valor", purchase.valorContratado(),
                        "statusDestinoId", targetStatusId
                ), Map.of("COMPRA", purchase.id())
        );
        return approval(approvalId);
    }

    private ApprovalRule matchingRule(PurchaseResponse purchase) {
        List<ApprovalRule> rules = jdbc.query(
                """
                SELECT r.*,
                       ((r.centro_custo_id IS NOT NULL) * 4
                        + (r.categoria_id IS NOT NULL) * 2
                        + (r.prioridade IS NOT NULL)) AS especificidade
                FROM finance_regra_aprovacao r
                WHERE r.obra_id = ? AND r.ativo = TRUE
                  AND r.moeda = ?
                  AND r.valor_minimo <= ?
                  AND (r.valor_maximo IS NULL OR r.valor_maximo >= ?)
                  AND (r.centro_custo_id IS NULL OR r.centro_custo_id = ?)
                  AND (r.categoria_id IS NULL OR r.categoria_id = ?)
                  AND (r.prioridade IS NULL OR r.prioridade = ?)
                  AND r.vigente_de <= CURRENT_TIMESTAMP(6)
                  AND (r.vigente_ate IS NULL
                       OR r.vigente_ate > CURRENT_TIMESTAMP(6))
                ORDER BY especificidade DESC, r.valor_minimo DESC,
                         r.vigente_de DESC, r.id
                LIMIT 1
                """,
                (rs, row) -> new ApprovalRule(
                        rs.getString("id"), rs.getBigDecimal("valor_minimo")
                ),
                purchase.obraId(), purchase.moeda(), purchase.valorContratado(),
                purchase.valorContratado(), purchase.centroCustoId(),
                purchase.categoriaId(), purchase.prioridade()
        );
        return rules.isEmpty() ? null : rules.getFirst();
    }

    private Transition transition(
            String obraId,
            String aggregate,
            String from,
            String to
    ) {
        List<Transition> transitions = jdbc.query(
                """
                SELECT exige_aprovacao FROM finance_status_transicao
                WHERE obra_id = ? AND agregado_tipo = ?
                  AND status_origem_id = ? AND status_destino_id = ?
                  AND ativo = TRUE
                """,
                (rs, row) -> new Transition(rs.getBoolean(1)),
                obraId, aggregate, from, to
        );
        if (transitions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A transição de status não está configurada para esta obra."
            );
        }
        return transitions.getFirst();
    }

    private Approval approvalFor(String purchaseId, String targetStatusId) {
        List<Approval> approvals = jdbc.query(
                """
                SELECT id, status FROM finance_aprovacao
                WHERE compra_id = ? AND status_destino_id = ?
                ORDER BY criado_em DESC LIMIT 1
                """,
                (rs, row) -> new Approval(rs.getString(1), rs.getString(2)),
                purchaseId, targetStatusId
        );
        return approvals.isEmpty() ? null : approvals.getFirst();
    }

    private Approval pendingApproval(String purchaseId) {
        List<Approval> approvals = jdbc.query(
                """
                SELECT id, status FROM finance_aprovacao
                WHERE compra_id = ? AND status = 'PENDENTE'
                ORDER BY criado_em DESC LIMIT 1
                """,
                (rs, row) -> new Approval(rs.getString(1), rs.getString(2)),
                purchaseId
        );
        return approvals.isEmpty() ? null : approvals.getFirst();
    }

    private Approval approval(String id) {
        return jdbc.queryForObject(
                "SELECT id, status FROM finance_aprovacao WHERE id = ?",
                (rs, row) -> new Approval(rs.getString(1), rs.getString(2)),
                id
        );
    }

    private ApprovalStage pendingStage(String approvalId) {
        List<ApprovalStage> stages = jdbc.query(
                """
                SELECT * FROM finance_aprovacao_etapa
                WHERE aprovacao_id = ? AND status = 'PENDENTE'
                ORDER BY ordem LIMIT 1
                """,
                this::mapStage,
                approvalId
        );
        return stages.isEmpty() ? null : stages.getFirst();
    }

    private ApprovalStage stageAfter(String approvalId, int order) {
        List<ApprovalStage> stages = jdbc.query(
                """
                SELECT * FROM finance_aprovacao_etapa
                WHERE aprovacao_id = ? AND ordem > ?
                ORDER BY ordem LIMIT 1
                """,
                this::mapStage,
                approvalId, order
        );
        return stages.isEmpty() ? null : stages.getFirst();
    }

    private ApprovalStage stage(String id) {
        return jdbc.queryForObject(
                "SELECT * FROM finance_aprovacao_etapa WHERE id = ?",
                this::mapStage,
                id
        );
    }

    private ApprovalStage mapStage(ResultSet rs, int row) throws SQLException {
        return new ApprovalStage(
                rs.getString("id"), rs.getInt("ordem"),
                rs.getString("aprovador_tipo"),
                rs.getString("aprovador_colaborador_id"),
                rs.getInt("aprovacoes_necessarias"),
                rs.getString("status")
        );
    }

    private ExistingDecision decisionByMutation(
            String actorId,
            String mutationId
    ) {
        List<ExistingDecision> decisions = jdbc.query(
                """
                SELECT d.decisao, d.justificativa, d.decidido_em, a.compra_id,
                       a.id AS aprovacao_id, a.status AS aprovacao_status,
                       e.id AS etapa_id, e.status AS etapa_status
                FROM finance_aprovacao_decisao d
                JOIN finance_aprovacao_etapa e
                  ON e.id = d.aprovacao_etapa_id
                JOIN finance_aprovacao a ON a.id = e.aprovacao_id
                WHERE d.decidido_por = ? AND d.client_mutation_id = ?
                """,
                (rs, row) -> new ExistingDecision(
                        rs.getString("compra_id"),
                        rs.getString("decisao"),
                        rs.getString("justificativa"),
                        new ApprovalDecisionResponse(
                                rs.getString("aprovacao_id"),
                                rs.getString("etapa_id"),
                                rs.getString("aprovacao_status"),
                                rs.getString("etapa_status"),
                                rs.getString("decisao"),
                                rs.getTimestamp("decidido_em").toLocalDateTime()
                        )
                ),
                actorId,
                mutationId
        );
        return decisions.isEmpty() ? null : decisions.getFirst();
    }

    private StatusChangeResponse statusResponse(
            PurchaseResponse purchase,
            boolean changed,
            String approvalId,
            String approvalStatus
    ) {
        return new StatusChangeResponse(
                purchase.id(), purchase.statusId(), purchase.statusCodigo(),
                purchase.versao(), changed, approvalId, approvalStatus
        );
    }

    private void saveSolicitationItems(
            String solicitationId,
            List<LineItemRequest> items,
            String actorId
    ) {
        saveItems(
                "finance_solicitacao_item", "solicitacao_id", solicitationId,
                items, actorId, true
        );
    }

    private void savePurchaseItems(
            String purchaseId,
            List<LineItemRequest> items,
            String actorId
    ) {
        saveItems(
                "finance_compra_item", "compra_id", purchaseId,
                items, actorId, false
        );
    }

    private void saveItems(
            String table,
            String parentColumn,
            String parentId,
            List<LineItemRequest> items,
            String actorId,
            boolean forecast
    ) {
        List<LineItemRequest> values = items == null ? List.of() : items;
        int order = 0;
        for (LineItemRequest item : values) {
            if (item == null || item.quantidade() == null
                    || item.quantidade().signum() <= 0
                    || item.quantidade().scale() > 4) {
                throw FinanceValidation.badRequest("Quantidade de item inválida.");
            }
            String description = FinanceValidation.requiredText(
                    item.descricao(), "item.descricao", 1000
            );
            String unit = FinanceValidation.requiredText(
                    item.unidade(), "item.unidade", 40
            );
            BigDecimal unitValue = FinanceValidation.optionalMoney(
                    item.valorUnitario(), "item.valorUnitario"
            );
            BigDecimal totalValue = FinanceValidation.optionalMoney(
                    item.valorTotal(), "item.valorTotal"
            );
            String nature = forecast
                    ? null
                    : purchaseItemNature(item.natureza());
            if (!forecast && (unitValue == null || totalValue == null)) {
                throw FinanceValidation.badRequest(
                        "Itens de compra exigem valores unitário e total."
                );
            }
            if (!forecast && "CAPITALIZAVEL".equals(nature)) {
                try {
                    if (item.quantidade().stripTrailingZeros().intValueExact() <= 0) {
                        throw new ArithmeticException("non-positive");
                    }
                } catch (ArithmeticException exception) {
                    throw FinanceValidation.badRequest(
                            "Item capitalizável exige quantidade inteira e positiva."
                    );
                }
            }
            String existingId = jdbc.query(
                    "SELECT id FROM " + table + " WHERE " + parentColumn
                            + " = ? AND ordem = ? LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null,
                    parentId, order
            );
            if (existingId == null) {
                String itemId = item.id() == null || item.id().isBlank()
                        ? UUID.randomUUID().toString()
                        : FinanceValidation.uuid(item.id(), "item.id");
                if (forecast) {
                    jdbc.update(
                            "INSERT INTO " + table + " (id, " + parentColumn
                                    + ", ordem, descricao, quantidade, unidade, "
                                    + "valor_unitario_previsto, "
                                    + "valor_total_previsto, criado_por) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            itemId, parentId, order, description,
                            item.quantidade(), unit, unitValue,
                            totalValue, actorId
                    );
                } else {
                    jdbc.update(
                            "INSERT INTO " + table + " (id, " + parentColumn
                                    + ", ordem, descricao, quantidade, unidade, "
                                    + "valor_unitario, valor_total, natureza, "
                                    + "criado_por) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            itemId, parentId, order, description,
                            item.quantidade(), unit, unitValue,
                            totalValue, nature, actorId
                    );
                }
            } else {
                if (!forecast) {
                    requireLinkedAssetConsistency(
                            existingId,
                            item.quantidade(),
                            nature
                    );
                }
                jdbc.update(
                        "UPDATE " + table + " SET descricao = ?, quantidade = ?, "
                                + "unidade = ?, "
                                + (forecast
                                ? "valor_unitario_previsto = ?, valor_total_previsto = ?"
                                : "valor_unitario = ?, valor_total = ?, natureza = ?")
                                + ", arquivado_em = NULL, atualizado_por = ?, "
                                + "versao_linha = versao_linha + 1 WHERE id = ?",
                        forecast
                                ? new Object[]{
                                    description, item.quantidade(), unit,
                                    unitValue, totalValue, actorId, existingId
                                }
                                : new Object[]{
                                    description, item.quantidade(), unit,
                                    unitValue, totalValue, nature,
                                    actorId, existingId
                                }
                );
            }
            order++;
        }
        if (!forecast && hasLinkedAssetsAtOrAfter(parentId, order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Itens com ativos confirmados não podem ser removidos da compra."
            );
        }
        jdbc.update(
                "UPDATE " + table + " SET arquivado_em = CURRENT_TIMESTAMP(6), "
                        + "atualizado_por = ?, versao_linha = versao_linha + 1 "
                        + "WHERE " + parentColumn + " = ? AND ordem >= ? "
                        + "AND arquivado_em IS NULL",
                actorId, parentId, order
        );
    }

    private String purchaseItemNature(String value) {
        if (value == null || value.isBlank()) {
            return "CONSUMO";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"CONSUMO".equals(normalized)
                && !"CAPITALIZAVEL".equals(normalized)) {
            throw FinanceValidation.badRequest(
                    "item.natureza deve ser CONSUMO ou CAPITALIZAVEL."
            );
        }
        return normalized;
    }

    private void requireLinkedAssetConsistency(
            String itemId,
            BigDecimal quantity,
            String nature
    ) {
        Integer linked = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_compra_item_ativo
                WHERE compra_item_id = ?
                """,
                Integer.class,
                itemId
        );
        int count = linked == null ? 0 : linked;
        if (count == 0) {
            return;
        }
        int exactQuantity;
        try {
            exactQuantity = quantity.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A quantidade de item com ativos confirmados deve permanecer inteira."
            );
        }
        if (!"CAPITALIZAVEL".equals(nature) || exactQuantity != count) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Natureza e quantidade não podem divergir dos ativos já confirmados."
            );
        }
    }

    private boolean hasLinkedAssetsAtOrAfter(String purchaseId, int order) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM finance_compra_item i
                JOIN finance_compra_item_ativo a ON a.compra_item_id = i.id
                WHERE i.compra_id = ? AND i.ordem >= ?
                """,
                Integer.class,
                purchaseId,
                order
        );
        return count != null && count > 0;
    }

    private ValidatedSolicitation validate(SolicitationRequest request) {
        if (request == null) {
            throw FinanceValidation.badRequest("Solicitação é obrigatória.");
        }
        String priority = FinanceValidation.priority(request.prioridade(), false);
        boolean explicitId = request.id() != null && !request.id().isBlank();
        return new ValidatedSolicitation(
                !explicitId
                        ? UUID.randomUUID().toString()
                        : FinanceValidation.uuid(request.id(), "id"),
                FinanceValidation.mutationId(request.clientMutationId()),
                FinanceValidation.uuid(request.obraId(), "obraId"),
                FinanceValidation.uuid(request.centroCustoId(), "centroCustoId"),
                FinanceValidation.uuid(request.categoriaId(), "categoriaId"),
                FinanceValidation.optionalUuid(request.fornecedorId(), "fornecedorId"),
                FinanceValidation.uuid(request.solicitanteId(), "solicitanteId"),
                FinanceValidation.optionalUuid(
                        request.responsavelCompraId(), "responsavelCompraId"
                ),
                FinanceValidation.uuid(request.statusId(), "statusId"),
                FinanceValidation.requiredText(request.titulo(), "titulo", 255),
                FinanceValidation.optionalText(request.descricao(), "descricao", 20_000),
                priority,
                FinanceValidation.currency(request.moeda()),
                FinanceValidation.money(request.valorPrevisto(), "valorPrevisto"),
                FinanceValidation.optionalMoney(request.valorAprovado(), "valorAprovado"),
                FinanceValidation.optionalMoney(request.valorContratado(), "valorContratado"),
                FinanceValidation.optionalMoney(request.valorPago(), "valorPago"),
                request.necessarioEm(),
                request.itens() == null ? List.of() : List.copyOf(request.itens()),
                explicitId
        );
    }

    private ValidatedPurchase validate(PurchaseRequest request) {
        if (request == null) {
            throw FinanceValidation.badRequest("Compra é obrigatória.");
        }
        boolean explicitId = request.id() != null && !request.id().isBlank();
        return new ValidatedPurchase(
                !explicitId
                        ? UUID.randomUUID().toString()
                        : FinanceValidation.uuid(request.id(), "id"),
                FinanceValidation.mutationId(request.clientMutationId()),
                FinanceValidation.optionalUuid(request.solicitacaoId(), "solicitacaoId"),
                FinanceValidation.uuid(request.obraId(), "obraId"),
                FinanceValidation.uuid(request.centroCustoId(), "centroCustoId"),
                FinanceValidation.uuid(request.categoriaId(), "categoriaId"),
                FinanceValidation.uuid(request.fornecedorId(), "fornecedorId"),
                FinanceValidation.uuid(
                        request.responsavelCompraId(), "responsavelCompraId"
                ),
                FinanceValidation.uuid(request.statusId(), "statusId"),
                FinanceValidation.optionalText(request.numeroPedido(), "numeroPedido", 120),
                FinanceValidation.requiredText(request.descricao(), "descricao", 1000),
                FinanceValidation.priority(request.prioridade(), false),
                FinanceValidation.currency(request.moeda()),
                FinanceValidation.optionalMoney(request.valorPrevisto(), "valorPrevisto"),
                FinanceValidation.optionalMoney(request.valorAprovado(), "valorAprovado"),
                FinanceValidation.money(request.valorContratado(), "valorContratado"),
                FinanceValidation.optionalMoney(request.valorPago(), "valorPago"),
                request.pedidoEmitidoEm(), request.entregaPrevistaEm(),
                request.itens() == null ? List.of() : List.copyOf(request.itens()),
                explicitId
        );
    }

    private SolicitationResponse solicitation(String id) {
        SolicitationResponse response = findSolicitation(id);
        if (response == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Solicitação de compra não encontrada."
            );
        }
        return response;
    }

    private SolicitationResponse findSolicitation(String id) {
        List<SolicitationResponse> values = jdbc.query(
                SOLICITATION_SELECT + " WHERE s.id = ?",
                this::mapSolicitation,
                id
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private SolicitationResponse mapSolicitation(ResultSet rs, int row)
            throws SQLException {
        String id = rs.getString("id");
        return new SolicitationResponse(
                id, rs.getString("obra_id"), rs.getString("obra_nome"),
                rs.getString("centro_custo_id"),
                rs.getString("centro_custo_nome"),
                rs.getString("categoria_id"), rs.getString("categoria_nome"),
                rs.getString("fornecedor_id"), rs.getString("fornecedor_nome"),
                rs.getString("solicitante_id"), rs.getString("solicitante_nome"),
                rs.getString("responsavel_compra_id"),
                rs.getString("responsavel_nome"), rs.getString("status_id"),
                rs.getString("status_codigo"), rs.getString("status_nome"),
                rs.getString("titulo"), rs.getString("descricao"),
                rs.getString("prioridade"), rs.getString("moeda"),
                rs.getBigDecimal("valor_previsto"),
                rs.getBigDecimal("valor_aprovado"),
                rs.getBigDecimal("valor_contratado"),
                rs.getBigDecimal("valor_pago"),
                date(rs, "necessario_em"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime(),
                rs.getLong("versao_linha"), solicitationItems(id)
        );
    }

    private PurchaseResponse purchase(String id) {
        PurchaseResponse response = findPurchase(id);
        if (response == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Compra não encontrada."
            );
        }
        return response;
    }

    private PurchaseResponse findPurchase(String id) {
        List<PurchaseResponse> values = jdbc.query(
                PURCHASE_SELECT + " WHERE c.id = ?",
                this::mapPurchase,
                id
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private PurchaseResponse mapPurchase(ResultSet rs, int row)
            throws SQLException {
        String id = rs.getString("id");
        return new PurchaseResponse(
                id, rs.getString("solicitacao_id"), rs.getString("obra_id"),
                rs.getString("obra_nome"), rs.getString("centro_custo_id"),
                rs.getString("centro_custo_nome"), rs.getString("categoria_id"),
                rs.getString("categoria_nome"), rs.getString("fornecedor_id"),
                rs.getString("fornecedor_nome"),
                rs.getString("responsavel_compra_id"),
                rs.getString("responsavel_nome"), rs.getString("status_id"),
                rs.getString("status_codigo"), rs.getString("status_nome"),
                rs.getString("numero_pedido"), rs.getString("descricao"),
                rs.getString("prioridade"), rs.getString("moeda"),
                rs.getBigDecimal("valor_previsto"),
                rs.getBigDecimal("valor_aprovado"),
                rs.getBigDecimal("valor_contratado"),
                rs.getBigDecimal("valor_pago"),
                date(rs, "pedido_emitido_em"),
                date(rs, "entrega_prevista_em"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime(),
                rs.getLong("versao_linha"), purchaseItems(id)
        );
    }

    private List<LineItemRequest> solicitationItems(String id) {
        return jdbc.query(
                """
                SELECT * FROM finance_solicitacao_item
                WHERE solicitacao_id = ? AND arquivado_em IS NULL
                ORDER BY ordem
                """,
                (rs, row) -> new LineItemRequest(
                        rs.getString("id"), rs.getString("descricao"),
                        rs.getBigDecimal("quantidade"), rs.getString("unidade"),
                        rs.getBigDecimal("valor_unitario_previsto"),
                        rs.getBigDecimal("valor_total_previsto"),
                        null
                ),
                id
        );
    }

    private List<LineItemRequest> purchaseItems(String id) {
        return jdbc.query(
                """
                SELECT * FROM finance_compra_item
                WHERE compra_id = ? AND arquivado_em IS NULL
                ORDER BY ordem
                """,
                (rs, row) -> new LineItemRequest(
                        rs.getString("id"), rs.getString("descricao"),
                        rs.getBigDecimal("quantidade"), rs.getString("unidade"),
                        rs.getBigDecimal("valor_unitario"),
                        rs.getBigDecimal("valor_total"),
                        rs.getString("natureza")
                ),
                id
        );
    }

    private void recordHistory(
            String entityType,
            String entityId,
            String obraId,
            String previousStatusId,
            String newStatusId,
            FinanceAuditContext audit,
            String mutationId,
            Map<String, ?> previous,
            Map<String, ?> next,
            String operation,
            String requestHash
    ) {
        Map<String, Object> boundNext = new LinkedHashMap<>();
        if (next != null) {
            boundNext.putAll(next);
        }
        boundNext.put("_mutationOperation", operation);
        boundNext.put("_mutationEntityId", entityId);
        boundNext.put("_mutationRequestHash", requestHash);
        try {
            jdbc.update(
                    """
                    INSERT INTO finance_status_historico (
                        id, entidade_tipo, entidade_id, obra_id,
                        status_anterior_id, status_novo_id, ator_id, origem,
                        dispositivo_id, client_mutation_id, correlacao_id,
                        estado_anterior_json, estado_novo_json, resultado
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON),
                              CAST(? AS JSON), 'SUCESSO')
                    """,
                    UUID.randomUUID().toString(), entityType, entityId, obraId,
                    previousStatusId, newStatusId, audit.actorId(), audit.origin(),
                    audit.deviceId(), mutationId, audit.correlationId(),
                    json(previous), json(boundNext)
            );
        } catch (DuplicateKeyException duplicate) {
            MutationReceipt receipt = mutationReceipt(
                    entityType,
                    audit.actorId(),
                    mutationId
            );
            requireMatchingMutation(
                    receipt,
                    entityId,
                    true,
                    operation,
                    requestHash
            );
        }
    }

    private MutationReceipt mutationReceipt(
            String type,
            String actorId,
            String mutationId
    ) {
        return jdbc.query(
                """
                SELECT entidade_id, estado_novo_json
                FROM finance_status_historico
                WHERE ator_id = ? AND client_mutation_id = ?
                  AND entidade_tipo = ? LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    JsonNode state;
                    try {
                        state = objectMapper.readTree(rs.getString("estado_novo_json"));
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException(
                                "Histórico financeiro possui JSON inválido.",
                                exception
                        );
                    }
                    return new MutationReceipt(
                            rs.getString("entidade_id"),
                            state.path("_mutationOperation").asText(null),
                            state.path("_mutationRequestHash").asText(null)
                    );
                },
                actorId, mutationId, type
        );
    }

    private void requireMatchingMutation(
            MutationReceipt receipt,
            String entityId,
            boolean explicitEntityId,
            String operation,
            String requestHash
    ) {
        if (receipt == null
                || (explicitEntityId && !Objects.equals(receipt.entityId(), entityId))
                || !Objects.equals(receipt.operation(), operation)
                || !Objects.equals(receipt.requestHash(), requestHash)) {
            throw mutationConflict();
        }
    }

    private String mutationPayloadHash(String operation, Object payload) {
        ObjectNode material = objectMapper.createObjectNode();
        material.put("operation", operation);
        material.set("payload", objectMapper.valueToTree(payload));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            objectMapper.writeValueAsBytes(material)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Falha ao vincular o conteúdo da mutação financeira.",
                    exception
            );
        }
    }

    private ResponseStatusException mutationConflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "clientMutationId já foi usado com outro conteúdo."
        );
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Falha ao serializar histórico financeiro.", exception);
        }
    }

    private void requireActor(FinanceAuditContext audit) {
        String actor = currentUser.requireUserId();
        if (audit == null || !actor.equals(audit.actorId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Ator financeiro divergente da sessão."
            );
        }
    }

    private boolean sameSolicitation(
            SolicitationResponse existing,
            ValidatedSolicitation input
    ) {
        return (!input.explicitId() || existing.id().equals(input.id()))
                && Objects.equals(existing.obraId(), input.obraId())
                && Objects.equals(existing.centroCustoId(), input.centroCustoId())
                && Objects.equals(existing.categoriaId(), input.categoriaId())
                && Objects.equals(existing.fornecedorId(), input.fornecedorId())
                && Objects.equals(existing.solicitanteId(), input.solicitanteId())
                && Objects.equals(
                        existing.responsavelCompraId(),
                        input.responsavelCompraId()
                )
                && Objects.equals(existing.statusId(), input.statusId())
                && Objects.equals(existing.titulo(), input.titulo())
                && Objects.equals(existing.descricao(), input.descricao())
                && Objects.equals(existing.prioridade(), input.prioridade())
                && Objects.equals(existing.moeda(), input.moeda())
                && sameDecimal(existing.valorPrevisto(), input.valorPrevisto())
                && sameDecimal(existing.valorAprovado(), input.valorAprovado())
                && sameDecimal(existing.valorContratado(), input.valorContratado())
                && sameDecimal(existing.valorPago(), input.valorPago())
                && Objects.equals(existing.necessarioEm(), input.necessarioEm())
                && sameItems(existing.itens(), input.itens(), true);
    }

    private boolean samePurchase(PurchaseResponse existing, ValidatedPurchase input) {
        return (!input.explicitId() || existing.id().equals(input.id()))
                && Objects.equals(existing.solicitacaoId(), input.solicitacaoId())
                && Objects.equals(existing.obraId(), input.obraId())
                && Objects.equals(existing.centroCustoId(), input.centroCustoId())
                && Objects.equals(existing.categoriaId(), input.categoriaId())
                && Objects.equals(existing.fornecedorId(), input.fornecedorId())
                && Objects.equals(
                        existing.responsavelCompraId(),
                        input.responsavelCompraId()
                )
                && Objects.equals(existing.statusId(), input.statusId())
                && Objects.equals(existing.numeroPedido(), input.numeroPedido())
                && Objects.equals(existing.descricao(), input.descricao())
                && Objects.equals(existing.prioridade(), input.prioridade())
                && Objects.equals(existing.moeda(), input.moeda())
                && sameDecimal(existing.valorPrevisto(), input.valorPrevisto())
                && sameDecimal(existing.valorAprovado(), input.valorAprovado())
                && sameDecimal(existing.valorContratado(), input.valorContratado())
                && sameDecimal(existing.valorPago(), input.valorPago())
                && Objects.equals(existing.pedidoEmitidoEm(), input.pedidoEmitidoEm())
                && Objects.equals(
                        existing.entregaPrevistaEm(),
                        input.entregaPrevistaEm()
                )
                && sameItems(existing.itens(), input.itens(), false);
    }

    private boolean sameItems(
            List<LineItemRequest> stored,
            List<LineItemRequest> requested,
            boolean forecast
    ) {
        if (stored.size() != requested.size()) {
            return false;
        }
        for (int index = 0; index < stored.size(); index++) {
            LineItemRequest persisted = stored.get(index);
            LineItemRequest input = requested.get(index);
            if (input == null
                    || (input.id() != null && !input.id().isBlank()
                            && !Objects.equals(
                                    persisted.id(),
                                    FinanceValidation.uuid(input.id(), "item.id")
                            ))
                    || !Objects.equals(
                            persisted.descricao(),
                            FinanceValidation.requiredText(
                                    input.descricao(), "item.descricao", 1000
                            )
                    )
                    || !sameDecimal(persisted.quantidade(), input.quantidade())
                    || !Objects.equals(
                            persisted.unidade(),
                            FinanceValidation.requiredText(
                                    input.unidade(), "item.unidade", 40
                            )
                    )
                    || !sameDecimal(
                            persisted.valorUnitario(),
                            FinanceValidation.optionalMoney(
                                    input.valorUnitario(), "item.valorUnitario"
                            )
                    )
                    || !sameDecimal(
                            persisted.valorTotal(),
                            FinanceValidation.optionalMoney(
                                    input.valorTotal(), "item.valorTotal"
                            )
                    )
                    || (!forecast && !Objects.equals(
                            persisted.natureza(),
                            purchaseItemNature(input.natureza())
                    ))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private Map<String, Object> solicitationState(SolicitationResponse value) {
        return state(
                "statusId", value.statusId(), "titulo", value.titulo(),
                "prioridade", value.prioridade(), "moeda", value.moeda(),
                "valorPrevisto", value.valorPrevisto(),
                "responsavelId", value.responsavelCompraId()
        );
    }

    private Map<String, Object> purchaseState(PurchaseResponse value) {
        return state(
                "statusId", value.statusId(), "numeroPedido", value.numeroPedido(),
                "prioridade", value.prioridade(), "moeda", value.moeda(),
                "valorContratado", value.valorContratado(),
                "valorPago", value.valorPago()
        );
    }

    private Map<String, Object> state(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            if (pairs[index + 1] != null) {
                values.put(pairs[index].toString(), pairs[index + 1]);
            }
        }
        return values;
    }

    private Map<String, String> related(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            if (pairs[index + 1] != null) {
                values.put(pairs[index], pairs[index + 1]);
            }
        }
        return values;
    }

    private void appendFilter(
            StringBuilder sql,
            List<Object> args,
            String field,
            LocalDate from,
            LocalDate to
    ) {
        if (from != null) {
            sql.append(" AND ").append(field).append(" >= ? ");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND ").append(field).append(" < ? ");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
    }

    private void appendEquals(
            StringBuilder sql,
            List<Object> args,
            String field,
            String value
    ) {
        if (value != null) {
            sql.append(" AND ").append(field).append(" = ? ");
            args.add(value);
        }
    }

    private LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getDate(column) == null ? null : rs.getDate(column).toLocalDate();
    }

    public record FinanceFilter(
            String obraId,
            LocalDate from,
            LocalDate to,
            String responsavelId,
            String fornecedorId,
            String statusId,
            String categoriaId,
            String centroCustoId,
            String prioridade,
            String query,
            Integer limit,
            Integer offset
    ) {
        FinanceFilter normalized() {
            String worksite = FinanceValidation.uuid(obraId, "obraId");
            if (from != null && to != null && to.isBefore(from)) {
                throw FinanceValidation.badRequest("Período inválido.");
            }
            int normalizedLimit = limit == null ? 100 : Math.min(200, Math.max(1, limit));
            int normalizedOffset = offset == null ? 0 : Math.max(0, offset);
            return new FinanceFilter(
                    worksite, from, to,
                    FinanceValidation.optionalUuid(responsavelId, "responsavelId"),
                    FinanceValidation.optionalUuid(fornecedorId, "fornecedorId"),
                    FinanceValidation.optionalUuid(statusId, "statusId"),
                    FinanceValidation.optionalUuid(categoriaId, "categoriaId"),
                    FinanceValidation.optionalUuid(centroCustoId, "centroCustoId"),
                    FinanceValidation.priority(prioridade, true),
                    FinanceValidation.optionalText(query, "query", 200),
                    normalizedLimit, normalizedOffset
            );
        }
    }

    private record ValidatedSolicitation(
            String id, String clientMutationId, String obraId,
            String centroCustoId, String categoriaId, String fornecedorId,
            String solicitanteId, String responsavelCompraId, String statusId,
            String titulo, String descricao, String prioridade, String moeda,
            BigDecimal valorPrevisto, BigDecimal valorAprovado,
            BigDecimal valorContratado, BigDecimal valorPago,
            LocalDate necessarioEm, List<LineItemRequest> itens,
            boolean explicitId
    ) {
    }

    private record ValidatedPurchase(
            String id, String clientMutationId, String solicitacaoId,
            String obraId, String centroCustoId, String categoriaId,
            String fornecedorId, String responsavelCompraId, String statusId,
            String numeroPedido, String descricao, String prioridade,
            String moeda, BigDecimal valorPrevisto, BigDecimal valorAprovado,
            BigDecimal valorContratado, BigDecimal valorPago,
            LocalDate pedidoEmitidoEm, LocalDate entregaPrevistaEm,
            List<LineItemRequest> itens, boolean explicitId
    ) {
    }

    private record MutationReceipt(
            String entityId,
            String operation,
            String requestHash
    ) {
    }

    private record Transition(boolean approvalRequired) {
    }

    private record ApprovalRule(String id, BigDecimal minimum) {
    }

    private record Approval(String id, String status) {
    }

    private record ApprovalStage(
            String id,
            int order,
            String approverType,
            String approverId,
            int requiredApprovals,
            String status
    ) {
    }

    private record ExistingDecision(
            String purchaseId,
            String decision,
            String justification,
            ApprovalDecisionResponse response
    ) {
    }
}

package com.projeto.cortex.financeiro.invoice;

import static com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinanceLedgerService {

    private static final Set<String> LEDGER_TYPES = Set.of("PAGAR", "RECEBER");
    private static final Set<String> LEDGER_ORIGINS = Set.of(
            "MANUAL", "NOTA_FISCAL", "COMPRA", "AJUSTE"
    );
    private static final Set<String> SETTLEMENT_TYPES = Set.of(
            "PAGAMENTO", "RECEBIMENTO"
    );
    private static final Set<String> SETTLEMENT_STATUSES = Set.of(
            "RASCUNHO", "AGENDADA", "EFETIVADA"
    );
    private static final String LEDGER_SELECT = """
            SELECT l.*, f.razao_social AS fornecedor_nome,
                   r.nome AS responsavel_nome,
                   st.codigo AS status_codigo,
                   st.nome AS status_nome
            FROM finance_lancamento l
            LEFT JOIN finance_fornecedor f ON f.id = l.fornecedor_id
            JOIN colaborador r ON r.id = l.responsavel_id
            JOIN finance_status_definicao st ON st.id = l.status_id
            """;

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final FinanceOntologyProjector ontology;
    private final ObjectMapper objectMapper;

    public FinanceLedgerService(
            JdbcTemplate jdbc,
            CurrentUserService currentUser,
            FinanceOntologyProjector ontology,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.ontology = ontology;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LedgerResponse saveLedger(
            LedgerRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        ValidatedLedger input = validateLedger(request);
        String replayId = ledgerMutationEntity(
                audit.actorId(), input.mutationId()
        );
        if (replayId != null) {
            LedgerResponse replay = getLedger(replayId);
            if (!sameLedger(replay, input)) {
                throw conflict(
                        "clientMutationId já foi usado com outro lançamento."
                );
            }
            return replay;
        }
        LedgerResponse previous = findLedger(input.id());
        boolean created = previous == null;
        requireLedgerStatus(input.worksiteId(), input.statusId());
        try {
            if (created) {
                if (input.baseVersion() != null) {
                    throw FinanceValidation.badRequest(
                            "baseVersao não deve ser enviada ao criar um lançamento."
                    );
                }
                jdbc.update(
                        """
                        INSERT INTO finance_lancamento (
                            id, client_mutation_id, obra_id, nota_fiscal_id,
                            fornecedor_id, centro_custo_id, categoria_id,
                            responsavel_id, status_id, tipo, origem,
                            numero_documento, descricao, data_competencia,
                            data_emissao, vencimento_em, moeda, valor_original,
                            desconto, juros, multa, valor_liquido, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                  ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        input.id(), input.mutationId(), input.worksiteId(),
                        input.invoiceId(), input.supplierId(),
                        input.costCenterId(), input.categoryId(),
                        input.responsibleId(), input.statusId(), input.type(),
                        input.origin(), input.documentNumber(),
                        input.description(), input.competenceDate(),
                        input.issueDate(), input.dueDate(), input.currency(),
                        input.original(), input.discount(), input.interest(),
                        input.fine(), input.net(), audit.actorId()
                );
            } else {
                requireSameWorksite(previous.obraId(), input.worksiteId());
                requireVersion(previous.versao(), input.baseVersion());
                if (previous.valorLiquidado().compareTo(input.net()) > 0) {
                    throw conflict(
                            "O novo valor não pode ser menor que o total já liquidado."
                    );
                }
                if (!previous.statusId().equals(input.statusId())) {
                    requireLedgerTransition(
                            input.worksiteId(), previous.statusId(), input.statusId()
                    );
                }
                int changed = jdbc.update(
                        """
                        UPDATE finance_lancamento
                        SET nota_fiscal_id = ?, fornecedor_id = ?,
                            centro_custo_id = ?, categoria_id = ?,
                            responsavel_id = ?, status_id = ?, tipo = ?,
                            origem = ?, numero_documento = ?, descricao = ?,
                            data_competencia = ?, data_emissao = ?,
                            vencimento_em = ?, moeda = ?, valor_original = ?,
                            desconto = ?, juros = ?, multa = ?,
                            valor_liquido = ?, atualizado_por = ?,
                            versao_linha = versao_linha + 1
                        WHERE id = ? AND versao_linha = ?
                          AND arquivado_em IS NULL
                        """,
                        input.invoiceId(), input.supplierId(),
                        input.costCenterId(), input.categoryId(),
                        input.responsibleId(), input.statusId(), input.type(),
                        input.origin(), input.documentNumber(),
                        input.description(), input.competenceDate(),
                        input.issueDate(), input.dueDate(), input.currency(),
                        input.original(), input.discount(), input.interest(),
                        input.fine(), input.net(), audit.actorId(), input.id(),
                        input.baseVersion()
                );
                if (changed != 1) {
                    throw conflict(
                            "O lançamento foi alterado em outro dispositivo."
                    );
                }
            }
            syncAllocations(input, audit.actorId());
            syncBudgetLinks(input, audit.actorId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "O lançamento conflita com outro registro ou referência da obra."
            );
        }
        LedgerResponse saved = getLedger(input.id());
        recordLedgerHistory(
                saved,
                created ? "LANCAMENTO_CRIADO" : "LANCAMENTO_ATUALIZADO",
                audit,
                input.mutationId(),
                previous == null ? Map.of() : ledgerState(previous),
                ledgerState(saved)
        );
        if (created || !previous.statusId().equals(saved.statusId())) {
            recordLedgerStatus(previous, saved, audit, input.mutationId());
        }
        ontology.success(
                "LANCAMENTO",
                saved.id(),
                saved.descricao(),
                created ? "LANCAMENTO_CRIADO" : "LANCAMENTO_ATUALIZADO",
                saved.obraId(),
                audit,
                previous == null ? Map.of() : ledgerState(previous),
                ledgerState(saved),
                ledgerRelated(saved)
        );
        return saved;
    }

    public LedgerResponse getLedger(String id) {
        LedgerResponse value = findLedger(FinanceValidation.uuid(id, "id"));
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Lançamento não encontrado."
            );
        }
        return value;
    }

    public List<LedgerResponse> listLedgers(LedgerFilter filter) {
        LedgerFilter value = filter.normalized();
        StringBuilder sql = new StringBuilder(LEDGER_SELECT)
                .append(" WHERE l.obra_id = ? AND l.arquivado_em IS NULL ");
        List<Object> args = new ArrayList<>();
        args.add(value.worksiteId());
        if (value.from() != null) {
            sql.append(" AND l.vencimento_em >= ? ");
            args.add(value.from());
        }
        if (value.to() != null) {
            sql.append(" AND l.vencimento_em <= ? ");
            args.add(value.to());
        }
        appendEquals(sql, args, "l.tipo", value.type());
        appendEquals(sql, args, "l.status_id", value.statusId());
        appendEquals(sql, args, "l.fornecedor_id", value.supplierId());
        appendEquals(sql, args, "l.responsavel_id", value.responsibleId());
        appendEquals(sql, args, "l.centro_custo_id", value.costCenterId());
        appendEquals(sql, args, "l.categoria_id", value.categoryId());
        if (value.query() != null) {
            sql.append("""
                     AND (
                        LOWER(l.descricao) LIKE ?
                        OR LOWER(COALESCE(l.numero_documento, '')) LIKE ?
                        OR LOWER(COALESCE(f.razao_social, '')) LIKE ?
                     )
                    """);
            String like = "%" + value.query().toLowerCase(Locale.ROOT) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY l.vencimento_em, l.id LIMIT ? OFFSET ?");
        args.add(value.limit());
        args.add(value.offset());
        LocalDate reference = value.referenceDate();
        return jdbc.query(
                sql.toString(),
                (rs, row) -> mapLedger(rs, row, reference),
                args.toArray()
        );
    }

    @Transactional
    public void archiveLedger(
            String ledgerId,
            long baseVersion,
            String mutationId,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String normalizedMutation = FinanceValidation.mutationId(mutationId);
        String replay = ledgerMutationEntity(
                audit.actorId(), normalizedMutation
        );
        if (replay != null) {
            if (!replay.equals(ledgerId)) {
                throw conflict("clientMutationId pertence a outro lançamento.");
            }
            return;
        }
        LedgerResponse ledger = getLedger(ledgerId);
        requireVersion(ledger.versao(), baseVersion);
        if (ledger.valorLiquidado().signum() > 0) {
            throw conflict(
                    "Lançamento com liquidação não pode ser arquivado."
            );
        }
        int changed = jdbc.update(
                """
                UPDATE finance_lancamento
                SET arquivado_por = ?, arquivado_em = CURRENT_TIMESTAMP(6),
                    atualizado_por = ?, versao_linha = versao_linha + 1
                WHERE id = ? AND versao_linha = ? AND arquivado_em IS NULL
                """,
                audit.actorId(), audit.actorId(), ledger.id(), baseVersion
        );
        if (changed != 1) {
            throw conflict("O lançamento foi alterado em outro dispositivo.");
        }
        Map<String, Object> archived = new LinkedHashMap<>(ledgerState(ledger));
        archived.put("arquivado", true);
        recordLedgerHistory(
                ledger, "LANCAMENTO_ARQUIVADO", audit, normalizedMutation,
                ledgerState(ledger), archived
        );
        ontology.success(
                "LANCAMENTO", ledger.id(), ledger.descricao(),
                "LANCAMENTO_ARQUIVADO", ledger.obraId(), audit,
                ledgerState(ledger), archived, ledgerRelated(ledger)
        );
    }

    @Transactional
    public SettlementResponse saveSettlement(
            SettlementRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        ValidatedSettlement input = validateSettlement(request);
        String replayId = settlementMutationEntity(
                audit.actorId(), input.mutationId()
        );
        if (replayId != null) {
            SettlementResponse replay = getSettlement(replayId);
            if (!sameSettlement(replay, input)) {
                throw conflict(
                        "clientMutationId já foi usado com outra liquidação."
                );
            }
            return replay;
        }
        SettlementResponse previous = findSettlement(input.id());
        boolean created = previous == null;
        boolean willApply = input.status().equals("EFETIVADA")
                && (previous == null || !previous.status().equals("EFETIVADA"));
        if (previous != null) {
            requireSameWorksite(previous.obraId(), input.worksiteId());
            requireVersion(previous.versao(), input.baseVersion());
            if (previous.status().equals("EFETIVADA")
                    || previous.status().equals("CANCELADA")) {
                throw conflict(
                        "Liquidação efetivada ou cancelada não pode ser editada."
                );
            }
        } else if (input.baseVersion() != null) {
            throw FinanceValidation.badRequest(
                    "baseVersao não deve ser enviada ao criar uma liquidação."
            );
        }
        List<LedgerSettlementTarget> targets = lockSettlementTargets(input);
        if (willApply) {
            requireAvailableBalances(input, targets);
        }
        try {
            if (created) {
                jdbc.update(
                        """
                        INSERT INTO finance_liquidacao (
                            id, client_mutation_id, obra_id, tipo, status,
                            data_liquidacao, moeda, valor_total, meio,
                            referencia_bancaria, observacoes,
                            efetivada_por, efetivada_em, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                  CASE WHEN ? = 'EFETIVADA'
                                       THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                                  ?)
                        """,
                        input.id(), input.mutationId(), input.worksiteId(),
                        input.type(), input.status(), input.date(),
                        input.currency(), input.total(), input.method(),
                        input.bankReference(), input.notes(),
                        willApply ? audit.actorId() : null,
                        input.status(), audit.actorId()
                );
            } else {
                int changed = jdbc.update(
                        """
                        UPDATE finance_liquidacao
                        SET tipo = ?, status = ?, data_liquidacao = ?,
                            moeda = ?, valor_total = ?, meio = ?,
                            referencia_bancaria = ?, observacoes = ?,
                            efetivada_por = ?,
                            efetivada_em = CASE WHEN ? = 'EFETIVADA'
                                THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                            atualizado_por = ?,
                            versao_linha = versao_linha + 1
                        WHERE id = ? AND versao_linha = ?
                          AND status IN ('RASCUNHO', 'AGENDADA')
                        """,
                        input.type(), input.status(), input.date(),
                        input.currency(), input.total(), input.method(),
                        input.bankReference(), input.notes(),
                        willApply ? audit.actorId() : null,
                        input.status(), audit.actorId(), input.id(),
                        input.baseVersion()
                );
                if (changed != 1) {
                    throw conflict(
                            "A liquidação foi alterada em outro dispositivo."
                    );
                }
            }
            syncSettlementAllocations(input, audit.actorId());
            if (willApply) {
                applySettlement(targets, audit.actorId(), true);
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "A liquidação conflita com outro registro ou saldo disponível."
            );
        }
        SettlementResponse saved = getSettlement(input.id());
        recordSettlementHistory(
                saved,
                willApply ? "LIQUIDACAO_EFETIVADA"
                        : (created ? "LIQUIDACAO_CRIADA" : "LIQUIDACAO_ATUALIZADA"),
                audit,
                input.mutationId(),
                previous == null ? Map.of() : settlementState(previous),
                settlementState(saved)
        );
        ontology.success(
                "LIQUIDACAO", saved.id(), saved.tipo(),
                willApply ? "LIQUIDACAO_EFETIVADA"
                        : (created ? "LIQUIDACAO_CRIADA" : "LIQUIDACAO_ATUALIZADA"),
                saved.obraId(), audit,
                previous == null ? Map.of() : settlementState(previous),
                settlementState(saved),
                Map.of("OBRA", saved.obraId())
        );
        return saved;
    }

    public SettlementResponse getSettlement(String id) {
        SettlementResponse value = findSettlement(
                FinanceValidation.uuid(id, "id")
        );
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Liquidação não encontrada."
            );
        }
        return value;
    }

    public List<SettlementResponse> listSettlements(
            String worksiteId,
            LocalDate from,
            LocalDate to,
            String type,
            String status,
            int limit,
            int offset
    ) {
        String normalizedWorksite = FinanceValidation.uuid(
                worksiteId, "obraId"
        );
        if (from != null && to != null && to.isBefore(from)) {
            throw FinanceValidation.badRequest("Período inválido.");
        }
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM finance_liquidacao WHERE obra_id = ?"
        );
        List<Object> args = new ArrayList<>();
        args.add(normalizedWorksite);
        if (from != null) {
            sql.append(" AND data_liquidacao >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND data_liquidacao <= ?");
            args.add(to);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND tipo = ?");
            args.add(FinanceInvoiceValidation.enumValue(
                    type, "tipo", SETTLEMENT_TYPES
            ));
        }
        if (status != null && !status.isBlank()) {
            String normalizedStatus = status.strip().toUpperCase(Locale.ROOT);
            if (!Set.of("RASCUNHO", "AGENDADA", "EFETIVADA", "CANCELADA")
                    .contains(normalizedStatus)) {
                throw FinanceValidation.badRequest("status inválido.");
            }
            sql.append(" AND status = ?");
            args.add(normalizedStatus);
        }
        sql.append(" ORDER BY data_liquidacao DESC, id DESC LIMIT ? OFFSET ?");
        args.add(Math.min(200, Math.max(1, limit)));
        args.add(Math.max(0, offset));
        return jdbc.query(sql.toString(), this::mapSettlement, args.toArray());
    }

    @Transactional
    public void cancelSettlement(
            String settlementId,
            CancelSettlementRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        if (request == null) {
            throw FinanceValidation.badRequest(
                    "Cancelamento é obrigatório."
            );
        }
        String mutationId = FinanceValidation.mutationId(
                request.clientMutationId()
        );
        String replay = settlementMutationEntity(audit.actorId(), mutationId);
        if (replay != null) {
            if (!replay.equals(settlementId)) {
                throw conflict(
                        "clientMutationId pertence a outra liquidação."
                );
            }
            return;
        }
        String reason = FinanceValidation.requiredText(
                request.motivo(), "motivo", 1000
        );
        SettlementResponse previous = getSettlement(settlementId);
        requireVersion(previous.versao(), request.baseVersao());
        if (previous.status().equals("CANCELADA")) {
            throw conflict("A liquidação já está cancelada.");
        }
        List<LedgerSettlementTarget> targets = lockSettlementTargets(
                new ValidatedSettlement(
                        previous.id(), mutationId, previous.obraId(),
                        previous.tipo(), previous.status(),
                        previous.dataLiquidacao(), previous.moeda(),
                        previous.valorTotal(), previous.meio(),
                        previous.referenciaBancaria(), previous.observacoes(),
                        previous.lancamentos().stream()
                                .map(value -> new ValidatedSettlementAllocation(
                                        value.id(), value.lancamentoId(),
                                        value.valorAplicado()
                                ))
                                .toList(),
                        previous.versao()
                )
        );
        if (previous.status().equals("EFETIVADA")) {
            applySettlement(targets, audit.actorId(), false);
        }
        int changed = jdbc.update(
                """
                UPDATE finance_liquidacao
                SET status = 'CANCELADA', cancelada_por = ?,
                    cancelada_em = CURRENT_TIMESTAMP(6),
                    motivo_cancelamento = ?, atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ? AND versao_linha = ? AND status <> 'CANCELADA'
                """,
                audit.actorId(), reason, audit.actorId(), previous.id(),
                previous.versao()
        );
        if (changed != 1) {
            throw conflict("A liquidação foi alterada em outro dispositivo.");
        }
        SettlementResponse cancelled = getSettlement(previous.id());
        recordSettlementHistory(
                cancelled, "LIQUIDACAO_CANCELADA", audit, mutationId,
                settlementState(previous), settlementState(cancelled)
        );
        ontology.success(
                "LIQUIDACAO", cancelled.id(), cancelled.tipo(),
                "LIQUIDACAO_CANCELADA", cancelled.obraId(), audit,
                settlementState(previous), settlementState(cancelled),
                Map.of("OBRA", cancelled.obraId())
        );
    }

    private ValidatedLedger validateLedger(LedgerRequest request) {
        if (request == null) {
            throw FinanceValidation.badRequest("Lançamento é obrigatório.");
        }
        if (request.dataCompetencia() == null) {
            throw FinanceValidation.badRequest(
                    "dataCompetencia é obrigatória."
            );
        }
        if (request.vencimentoEm() == null) {
            throw FinanceValidation.badRequest("vencimentoEm é obrigatório.");
        }
        BigDecimal net = FinanceInvoiceValidation.ledgerTotal(
                request.valorOriginal(), request.desconto(), request.juros(),
                request.multa(), request.valorLiquido()
        );
        List<ValidatedAllocation> allocations = validateAllocations(
                request.alocacoes(), net
        );
        List<ValidatedBudgetLink> budgetLinks = validateBudgetLinks(
                request.vinculosOrcamento(), net
        );
        return new ValidatedLedger(
                optionalId(request.id()),
                FinanceValidation.mutationId(request.clientMutationId()),
                FinanceValidation.uuid(request.obraId(), "obraId"),
                FinanceValidation.optionalUuid(
                        request.notaFiscalId(), "notaFiscalId"
                ),
                FinanceValidation.optionalUuid(
                        request.fornecedorId(), "fornecedorId"
                ),
                FinanceValidation.optionalUuid(
                        request.centroCustoId(), "centroCustoId"
                ),
                FinanceValidation.optionalUuid(
                        request.categoriaId(), "categoriaId"
                ),
                FinanceValidation.uuid(request.responsavelId(), "responsavelId"),
                FinanceValidation.uuid(request.statusId(), "statusId"),
                FinanceInvoiceValidation.enumValue(
                        request.tipo(), "tipo", LEDGER_TYPES
                ),
                FinanceInvoiceValidation.enumValue(
                        request.origem(), "origem", LEDGER_ORIGINS
                ),
                FinanceValidation.optionalText(
                        request.numeroDocumento(), "numeroDocumento", 120
                ),
                FinanceValidation.requiredText(
                        request.descricao(), "descricao", 1000
                ),
                request.dataCompetencia(), request.dataEmissao(),
                request.vencimentoEm(),
                FinanceValidation.currency(request.moeda()),
                FinanceValidation.money(
                        request.valorOriginal(), "valorOriginal"
                ),
                FinanceInvoiceValidation.zeroIfNull(
                        request.desconto(), "desconto"
                ),
                FinanceInvoiceValidation.zeroIfNull(request.juros(), "juros"),
                FinanceInvoiceValidation.zeroIfNull(request.multa(), "multa"),
                net, allocations, budgetLinks, request.baseVersao()
        );
    }

    private List<ValidatedAllocation> validateAllocations(
            List<AllocationRequest> requests,
            BigDecimal net
    ) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        FinanceInvoiceValidation.requireExactAllocation(
                net,
                requests.stream().map(AllocationRequest::valorAlocado).toList(),
                "alocações"
        );
        Set<String> keys = new HashSet<>();
        List<ValidatedAllocation> values = new ArrayList<>();
        for (AllocationRequest request : requests) {
            if (request == null) {
                throw FinanceValidation.badRequest("Alocação inválida.");
            }
            String center = FinanceValidation.uuid(
                    request.centroCustoId(), "centroCustoId"
            );
            String category = FinanceValidation.uuid(
                    request.categoriaId(), "categoriaId"
            );
            if (!keys.add(center + ":" + category)) {
                throw FinanceValidation.badRequest(
                        "Centro de custo e categoria estão duplicados."
                );
            }
            BigDecimal percentage = request.percentual() == null
                    ? null
                    : FinanceValidation.percentage(
                            request.percentual(), "percentual"
                    );
            values.add(new ValidatedAllocation(
                    optionalNullableId(request.id()), center, category,
                    FinanceValidation.money(
                            request.valorAlocado(), "valorAlocado"
                    ),
                    percentage
            ));
        }
        return List.copyOf(values);
    }

    private List<ValidatedBudgetLink> validateBudgetLinks(
            List<BudgetLinkRequest> requests,
            BigDecimal net
    ) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        Set<String> items = new HashSet<>();
        List<ValidatedBudgetLink> values = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (BudgetLinkRequest request : requests) {
            if (request == null) {
                throw FinanceValidation.badRequest(
                        "Vínculo orçamentário inválido."
                );
            }
            String item = FinanceValidation.uuid(
                    request.itemContratualId(), "itemContratualId"
            );
            if (!items.add(item)) {
                throw FinanceValidation.badRequest(
                        "Item orçamentário duplicado."
                );
            }
            BigDecimal amount = FinanceValidation.money(
                    request.valorVinculado(), "valorVinculado"
            );
            if (amount.signum() <= 0) {
                throw FinanceValidation.badRequest(
                        "valorVinculado deve ser maior que zero."
                );
            }
            total = total.add(amount);
            values.add(new ValidatedBudgetLink(
                    optionalNullableId(request.id()), item, amount
            ));
        }
        if (total.compareTo(net) > 0) {
            throw FinanceValidation.badRequest(
                    "Vínculos orçamentários não podem superar o lançamento."
            );
        }
        return List.copyOf(values);
    }

    private ValidatedSettlement validateSettlement(SettlementRequest request) {
        if (request == null) {
            throw FinanceValidation.badRequest("Liquidação é obrigatória.");
        }
        if (request.dataLiquidacao() == null) {
            throw FinanceValidation.badRequest(
                    "dataLiquidacao é obrigatória."
            );
        }
        BigDecimal total = FinanceValidation.money(
                request.valorTotal(), "valorTotal"
        );
        if (total.signum() <= 0) {
            throw FinanceValidation.badRequest(
                    "valorTotal deve ser maior que zero."
            );
        }
        if (request.lancamentos() == null || request.lancamentos().isEmpty()) {
            throw FinanceValidation.badRequest(
                    "Ao menos um lançamento deve ser informado."
            );
        }
        FinanceInvoiceValidation.requireExactAllocation(
                total,
                request.lancamentos().stream()
                        .map(SettlementAllocationRequest::valorAplicado)
                        .toList(),
                "liquidações"
        );
        Set<String> ledgers = new HashSet<>();
        List<ValidatedSettlementAllocation> allocations = new ArrayList<>();
        for (SettlementAllocationRequest allocation : request.lancamentos()) {
            String ledgerId = FinanceValidation.uuid(
                    allocation.lancamentoId(), "lancamentoId"
            );
            if (!ledgers.add(ledgerId)) {
                throw FinanceValidation.badRequest(
                        "Lançamento duplicado na liquidação."
                );
            }
            allocations.add(new ValidatedSettlementAllocation(
                    optionalNullableId(allocation.id()),
                    ledgerId,
                    FinanceValidation.money(
                            allocation.valorAplicado(), "valorAplicado"
                    )
            ));
        }
        return new ValidatedSettlement(
                optionalId(request.id()),
                FinanceValidation.mutationId(request.clientMutationId()),
                FinanceValidation.uuid(request.obraId(), "obraId"),
                FinanceInvoiceValidation.enumValue(
                        request.tipo(), "tipo", SETTLEMENT_TYPES
                ),
                FinanceInvoiceValidation.enumValue(
                        request.status(), "status", SETTLEMENT_STATUSES
                ),
                request.dataLiquidacao(),
                FinanceValidation.currency(request.moeda()),
                total,
                FinanceValidation.optionalText(request.meio(), "meio", 40),
                FinanceValidation.optionalText(
                        request.referenciaBancaria(),
                        "referenciaBancaria",
                        255
                ),
                FinanceValidation.optionalText(
                        request.observacoes(), "observacoes", 2000
                ),
                List.copyOf(allocations),
                request.baseVersao()
        );
    }

    private void syncAllocations(ValidatedLedger input, String actorId) {
        Map<String, String> existing = new LinkedHashMap<>();
        jdbc.query(
                "SELECT id, centro_custo_id, categoria_id FROM finance_lancamento_alocacao WHERE lancamento_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        existing.put(
                                rs.getString("centro_custo_id") + ":"
                                        + rs.getString("categoria_id"),
                                rs.getString("id")
                        ),
                input.id()
        );
        Set<String> kept = new HashSet<>();
        for (ValidatedAllocation allocation : input.allocations()) {
            String key = allocation.costCenterId() + ":" + allocation.categoryId();
            String existingId = existing.get(key);
            String id = existingId == null
                    ? Objects.requireNonNullElseGet(
                            allocation.id(), () -> UUID.randomUUID().toString()
                    )
                    : existingId;
            if (existingId == null) {
                jdbc.update(
                        """
                        INSERT INTO finance_lancamento_alocacao (
                            id, lancamento_id, obra_id, centro_custo_id,
                            categoria_id, valor_alocado, percentual, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        id, input.id(), input.worksiteId(),
                        allocation.costCenterId(), allocation.categoryId(),
                        allocation.amount(), allocation.percentage(), actorId
                );
            } else {
                jdbc.update(
                        """
                        UPDATE finance_lancamento_alocacao
                        SET valor_alocado = ?, percentual = ?,
                            atualizado_por = ?, arquivado_por = NULL,
                            arquivado_em = NULL,
                            versao_linha = versao_linha + 1
                        WHERE id = ?
                        """,
                        allocation.amount(), allocation.percentage(), actorId, id
                );
            }
            kept.add(key);
        }
        archiveMissing(
                "finance_lancamento_alocacao", existing, kept, actorId
        );
    }

    private void syncBudgetLinks(ValidatedLedger input, String actorId) {
        Map<String, String> existing = new LinkedHashMap<>();
        jdbc.query(
                "SELECT id, item_contratual_id FROM finance_lancamento_orcamento_vinculo WHERE lancamento_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        existing.put(
                                rs.getString("item_contratual_id"),
                                rs.getString("id")
                        ),
                input.id()
        );
        Set<String> kept = new HashSet<>();
        for (ValidatedBudgetLink link : input.budgetLinks()) {
            String existingId = existing.get(link.itemId());
            String id = existingId == null
                    ? Objects.requireNonNullElseGet(
                            link.id(), () -> UUID.randomUUID().toString()
                    )
                    : existingId;
            if (existingId == null) {
                jdbc.update(
                        """
                        INSERT INTO finance_lancamento_orcamento_vinculo (
                            id, lancamento_id, obra_id, item_contratual_id,
                            valor_vinculado, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        id, input.id(), input.worksiteId(), link.itemId(),
                        link.amount(), actorId
                );
            } else {
                jdbc.update(
                        """
                        UPDATE finance_lancamento_orcamento_vinculo
                        SET valor_vinculado = ?, arquivado_por = NULL,
                            arquivado_em = NULL,
                            versao_linha = versao_linha + 1
                        WHERE id = ?
                        """,
                        link.amount(), id
                );
            }
            kept.add(link.itemId());
        }
        archiveMissing(
                "finance_lancamento_orcamento_vinculo",
                existing,
                kept,
                actorId
        );
    }

    private void archiveMissing(
            String table,
            Map<String, String> existing,
            Set<String> kept,
            String actorId
    ) {
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            if (!kept.contains(entry.getKey())) {
                jdbc.update(
                        "UPDATE " + table + " SET arquivado_por = ?, "
                                + "arquivado_em = CURRENT_TIMESTAMP(6), "
                                + "versao_linha = versao_linha + 1 "
                                + "WHERE id = ? AND arquivado_em IS NULL",
                        actorId,
                        entry.getValue()
                );
            }
        }
    }

    private List<LedgerSettlementTarget> lockSettlementTargets(
            ValidatedSettlement settlement
    ) {
        List<LedgerSettlementTarget> targets = new ArrayList<>();
        for (ValidatedSettlementAllocation allocation : settlement.allocations()) {
            LedgerSettlementTarget target = jdbc.query(
                    """
                    SELECT id, obra_id, tipo, moeda, valor_liquido,
                           valor_liquidado
                    FROM finance_lancamento
                    WHERE id = ? AND arquivado_em IS NULL
                    FOR UPDATE
                    """,
                    rs -> rs.next()
                            ? new LedgerSettlementTarget(
                                    rs.getString("id"),
                                    rs.getString("obra_id"),
                                    rs.getString("tipo"),
                                    rs.getString("moeda"),
                                    rs.getBigDecimal("valor_liquido"),
                                    rs.getBigDecimal("valor_liquidado"),
                                    allocation.amount()
                            )
                            : null,
                    allocation.ledgerId()
            );
            if (target == null) {
                throw FinanceValidation.badRequest(
                        "Lançamento da liquidação não foi encontrado."
                );
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    private void requireAvailableBalances(
            ValidatedSettlement settlement,
            List<LedgerSettlementTarget> targets
    ) {
        String expectedLedgerType = settlement.type().equals("PAGAMENTO")
                ? "PAGAR" : "RECEBER";
        for (LedgerSettlementTarget target : targets) {
            if (!target.worksiteId().equals(settlement.worksiteId())
                    || !target.currency().equals(settlement.currency())
                    || !target.type().equals(expectedLedgerType)) {
                throw FinanceValidation.badRequest(
                        "Liquidação e lançamento possuem escopo, moeda ou tipo incompatível."
                );
            }
            BigDecimal available = target.net().subtract(target.settled());
            if (available.compareTo(target.applied()) < 0) {
                throw conflict(
                        "O valor aplicado supera o saldo aberto do lançamento."
                );
            }
        }
    }

    private void syncSettlementAllocations(
            ValidatedSettlement settlement,
            String actorId
    ) {
        Map<String, String> existing = new LinkedHashMap<>();
        jdbc.query(
                "SELECT id, lancamento_id FROM finance_liquidacao_lancamento WHERE liquidacao_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        existing.put(
                                rs.getString("lancamento_id"),
                                rs.getString("id")
                        ),
                settlement.id()
        );
        Set<String> kept = new HashSet<>();
        for (ValidatedSettlementAllocation allocation : settlement.allocations()) {
            String existingId = existing.get(allocation.ledgerId());
            String id = existingId == null
                    ? Objects.requireNonNullElseGet(
                            allocation.id(), () -> UUID.randomUUID().toString()
                    )
                    : existingId;
            if (existingId == null) {
                jdbc.update(
                        """
                        INSERT INTO finance_liquidacao_lancamento (
                            id, liquidacao_id, lancamento_id, obra_id,
                            valor_aplicado, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        id, settlement.id(), allocation.ledgerId(),
                        settlement.worksiteId(), allocation.amount(), actorId
                );
            } else {
                jdbc.update(
                        """
                        UPDATE finance_liquidacao_lancamento
                        SET valor_aplicado = ?, arquivado_por = NULL,
                            arquivado_em = NULL,
                            versao_linha = versao_linha + 1
                        WHERE id = ?
                        """,
                        allocation.amount(), id
                );
            }
            kept.add(allocation.ledgerId());
        }
        archiveMissing(
                "finance_liquidacao_lancamento", existing, kept, actorId
        );
    }

    private void applySettlement(
            List<LedgerSettlementTarget> targets,
            String actorId,
            boolean apply
    ) {
        for (LedgerSettlementTarget target : targets) {
            int changed = apply
                    ? jdbc.update(
                            """
                            UPDATE finance_lancamento
                            SET valor_liquidado = valor_liquidado + ?,
                                atualizado_por = ?,
                                versao_linha = versao_linha + 1
                            WHERE id = ?
                              AND valor_liquidado + ? <= valor_liquido
                            """,
                            target.applied(), actorId, target.id(),
                            target.applied()
                    )
                    : jdbc.update(
                            """
                            UPDATE finance_lancamento
                            SET valor_liquidado = valor_liquidado - ?,
                                atualizado_por = ?,
                                versao_linha = versao_linha + 1
                            WHERE id = ? AND valor_liquidado >= ?
                            """,
                            target.applied(), actorId, target.id(),
                            target.applied()
                    );
            if (changed != 1) {
                throw conflict(
                        "O saldo do lançamento mudou durante a liquidação."
                );
            }
        }
    }

    private LedgerResponse findLedger(String id) {
        List<LedgerResponse> values = jdbc.query(
                LEDGER_SELECT + " WHERE l.id = ? AND l.arquivado_em IS NULL",
                (rs, row) -> mapLedger(rs, row, LocalDate.now()),
                id
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private LedgerResponse mapLedger(
            ResultSet rs,
            int row,
            LocalDate reference
    ) throws SQLException {
        String id = rs.getString("id");
        BigDecimal net = rs.getBigDecimal("valor_liquido");
        BigDecimal settled = rs.getBigDecimal("valor_liquidado");
        BigDecimal open = net.subtract(settled);
        LocalDate due = date(rs, "vencimento_em");
        return new LedgerResponse(
                id, rs.getString("obra_id"), rs.getString("nota_fiscal_id"),
                rs.getString("fornecedor_id"), rs.getString("fornecedor_nome"),
                rs.getString("centro_custo_id"), rs.getString("categoria_id"),
                rs.getString("responsavel_id"), rs.getString("responsavel_nome"),
                rs.getString("status_id"), rs.getString("status_codigo"),
                rs.getString("status_nome"), rs.getString("tipo"),
                rs.getString("origem"), rs.getString("numero_documento"),
                rs.getString("descricao"), date(rs, "data_competencia"),
                date(rs, "data_emissao"), due, rs.getString("moeda"),
                rs.getBigDecimal("valor_original"), rs.getBigDecimal("desconto"),
                rs.getBigDecimal("juros"), rs.getBigDecimal("multa"), net,
                settled, open,
                open.signum() > 0 && due.isBefore(reference),
                allocations(id), budgetLinks(id), rs.getLong("versao_linha"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime()
        );
    }

    private List<AllocationResponse> allocations(String ledgerId) {
        return jdbc.query(
                """
                SELECT id, centro_custo_id, categoria_id, valor_alocado,
                       percentual
                FROM finance_lancamento_alocacao
                WHERE lancamento_id = ? AND arquivado_em IS NULL
                ORDER BY criado_em, id
                """,
                (rs, row) -> new AllocationResponse(
                        rs.getString("id"), rs.getString("centro_custo_id"),
                        rs.getString("categoria_id"),
                        rs.getBigDecimal("valor_alocado"),
                        rs.getBigDecimal("percentual")
                ),
                ledgerId
        );
    }

    private List<BudgetLinkResponse> budgetLinks(String ledgerId) {
        return jdbc.query(
                """
                SELECT v.id, v.item_contratual_id, i.codigo_item,
                       i.descricao, v.valor_vinculado
                FROM finance_lancamento_orcamento_vinculo v
                JOIN item_contratual i ON i.id = v.item_contratual_id
                WHERE v.lancamento_id = ? AND v.arquivado_em IS NULL
                ORDER BY v.criado_em, v.id
                """,
                (rs, row) -> new BudgetLinkResponse(
                        rs.getString("id"),
                        rs.getString("item_contratual_id"),
                        rs.getString("codigo_item"),
                        rs.getString("descricao"),
                        rs.getBigDecimal("valor_vinculado")
                ),
                ledgerId
        );
    }

    private SettlementResponse findSettlement(String id) {
        List<SettlementResponse> values = jdbc.query(
                "SELECT * FROM finance_liquidacao WHERE id = ?",
                this::mapSettlement,
                id
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private SettlementResponse mapSettlement(ResultSet rs, int row)
            throws SQLException {
        String id = rs.getString("id");
        return new SettlementResponse(
                id, rs.getString("obra_id"), rs.getString("tipo"),
                rs.getString("status"), date(rs, "data_liquidacao"),
                rs.getString("moeda"), rs.getBigDecimal("valor_total"),
                rs.getString("meio"), rs.getString("referencia_bancaria"),
                rs.getString("observacoes"), settlementAllocations(id),
                rs.getLong("versao_linha"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime()
        );
    }

    private List<SettlementAllocationResponse> settlementAllocations(
            String settlementId
    ) {
        return jdbc.query(
                """
                SELECT id, lancamento_id, valor_aplicado
                FROM finance_liquidacao_lancamento
                WHERE liquidacao_id = ? AND arquivado_em IS NULL
                ORDER BY criado_em, id
                """,
                (rs, row) -> new SettlementAllocationResponse(
                        rs.getString("id"),
                        rs.getString("lancamento_id"),
                        rs.getBigDecimal("valor_aplicado")
                ),
                settlementId
        );
    }

    private void requireLedgerStatus(String worksiteId, String statusId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_status_definicao
                WHERE id = ? AND obra_id = ? AND agregado_tipo = 'LANCAMENTO'
                  AND ativo = TRUE AND arquivado_em IS NULL
                """,
                Integer.class,
                statusId, worksiteId
        );
        if (count == null || count != 1) {
            throw FinanceValidation.badRequest(
                    "Status de lançamento inválido para esta obra."
            );
        }
    }

    private void requireLedgerTransition(
            String worksiteId,
            String from,
            String to
    ) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_status_transicao
                WHERE obra_id = ? AND agregado_tipo = 'LANCAMENTO'
                  AND status_origem_id = ? AND status_destino_id = ?
                  AND ativo = TRUE
                """,
                Integer.class,
                worksiteId, from, to
        );
        if (count == null || count != 1) {
            throw conflict("A transição de status não está autorizada.");
        }
    }

    private void recordLedgerHistory(
            LedgerResponse ledger,
            String action,
            FinanceAuditContext audit,
            String mutationId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        jdbc.update(
                """
                INSERT INTO finance_lancamento_historico (
                    id, lancamento_id, obra_id, acao, ator_id, origem,
                    dispositivo_id, client_mutation_id, correlacao_id,
                    estado_anterior_json, estado_novo_json, resultado
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,
                          'SUCESSO')
                """,
                UUID.randomUUID().toString(), ledger.id(), ledger.obraId(),
                action, audit.actorId(), audit.origin(), audit.deviceId(),
                mutationId, audit.correlationId(), json(before), json(after)
        );
    }

    private void recordLedgerStatus(
            LedgerResponse previous,
            LedgerResponse saved,
            FinanceAuditContext audit,
            String mutationId
    ) {
        jdbc.update(
                """
                INSERT INTO finance_status_historico (
                    id, entidade_tipo, entidade_id, obra_id,
                    status_anterior_id, status_novo_id, ator_id, origem,
                    dispositivo_id, client_mutation_id, correlacao_id,
                    estado_anterior_json, estado_novo_json, resultado
                ) VALUES (?, 'LANCAMENTO', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?::jsonb, ?::jsonb, 'SUCESSO')
                """,
                UUID.randomUUID().toString(), saved.id(), saved.obraId(),
                previous == null ? null : previous.statusId(), saved.statusId(),
                audit.actorId(), audit.origin(), audit.deviceId(), mutationId,
                audit.correlationId(),
                json(previous == null ? Map.of() : ledgerState(previous)),
                json(ledgerState(saved))
        );
    }

    private void recordSettlementHistory(
            SettlementResponse settlement,
            String action,
            FinanceAuditContext audit,
            String mutationId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        jdbc.update(
                """
                INSERT INTO finance_liquidacao_historico (
                    id, liquidacao_id, obra_id, acao, ator_id, origem,
                    dispositivo_id, client_mutation_id, correlacao_id,
                    estado_anterior_json, estado_novo_json, resultado
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,
                          'SUCESSO')
                """,
                UUID.randomUUID().toString(), settlement.id(),
                settlement.obraId(), action, audit.actorId(), audit.origin(),
                audit.deviceId(), mutationId, audit.correlationId(),
                json(before), json(after)
        );
    }

    private String ledgerMutationEntity(String actorId, String mutationId) {
        return jdbc.query(
                "SELECT lancamento_id FROM finance_lancamento_historico WHERE ator_id = ? AND client_mutation_id = ? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                actorId, mutationId
        );
    }

    private String settlementMutationEntity(String actorId, String mutationId) {
        return jdbc.query(
                "SELECT liquidacao_id FROM finance_liquidacao_historico WHERE ator_id = ? AND client_mutation_id = ? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                actorId, mutationId
        );
    }

    private boolean sameLedger(LedgerResponse value, ValidatedLedger input) {
        return value.obraId().equals(input.worksiteId())
                && Objects.equals(value.notaFiscalId(), input.invoiceId())
                && Objects.equals(value.fornecedorId(), input.supplierId())
                && Objects.equals(value.centroCustoId(), input.costCenterId())
                && Objects.equals(value.categoriaId(), input.categoryId())
                && value.responsavelId().equals(input.responsibleId())
                && value.statusId().equals(input.statusId())
                && value.tipo().equals(input.type())
                && value.origem().equals(input.origin())
                && Objects.equals(value.numeroDocumento(), input.documentNumber())
                && value.descricao().equals(input.description())
                && value.dataCompetencia().equals(input.competenceDate())
                && Objects.equals(value.dataEmissao(), input.issueDate())
                && value.vencimentoEm().equals(input.dueDate())
                && value.moeda().equals(input.currency())
                && value.valorOriginal().compareTo(input.original()) == 0
                && value.desconto().compareTo(input.discount()) == 0
                && value.juros().compareTo(input.interest()) == 0
                && value.multa().compareTo(input.fine()) == 0
                && value.valorLiquido().compareTo(input.net()) == 0;
    }

    private boolean sameSettlement(
            SettlementResponse value,
            ValidatedSettlement input
    ) {
        return value.obraId().equals(input.worksiteId())
                && value.tipo().equals(input.type())
                && value.status().equals(input.status())
                && value.dataLiquidacao().equals(input.date())
                && value.moeda().equals(input.currency())
                && value.valorTotal().compareTo(input.total()) == 0
                && Objects.equals(value.meio(), input.method())
                && Objects.equals(
                        value.referenciaBancaria(), input.bankReference()
                )
                && Objects.equals(value.observacoes(), input.notes());
    }

    private Map<String, Object> ledgerState(LedgerResponse value) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", value.id());
        state.put("obraId", value.obraId());
        state.put("tipo", value.tipo());
        state.put("statusId", value.statusId());
        state.put("statusCodigo", value.statusCodigo());
        state.put("moeda", value.moeda());
        state.put("valorLiquido", value.valorLiquido());
        state.put("valorLiquidado", value.valorLiquidado());
        state.put("vencimentoEm", value.vencimentoEm());
        state.put("versao", value.versao());
        return state;
    }

    private Map<String, String> ledgerRelated(LedgerResponse value) {
        Map<String, String> related = new LinkedHashMap<>();
        if (value.notaFiscalId() != null) {
            related.put("NOTA_FISCAL", value.notaFiscalId());
        }
        if (value.fornecedorId() != null) {
            related.put("FORNECEDOR", value.fornecedorId());
        }
        related.put("RESPONSAVEL", value.responsavelId());
        return related;
    }

    private Map<String, Object> settlementState(SettlementResponse value) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", value.id());
        state.put("obraId", value.obraId());
        state.put("tipo", value.tipo());
        state.put("status", value.status());
        state.put("dataLiquidacao", value.dataLiquidacao());
        state.put("moeda", value.moeda());
        state.put("valorTotal", value.valorTotal());
        state.put("versao", value.versao());
        return state;
    }

    private void appendEquals(
            StringBuilder sql,
            List<Object> args,
            String column,
            String value
    ) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ? ");
            args.add(value);
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

    private void requireSameWorksite(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw conflict("O registro financeiro pertence a outra obra.");
        }
    }

    private void requireVersion(long actual, Long requested) {
        if (requested == null || actual != requested) {
            throw conflict("A versão do registro está desatualizada.");
        }
    }

    private void requireVersion(long actual, long requested) {
        requireVersion(actual, Long.valueOf(requested));
    }

    private String optionalId(String value) {
        return value == null || value.isBlank()
                ? UUID.randomUUID().toString()
                : FinanceValidation.uuid(value, "id");
    }

    private String optionalNullableId(String value) {
        return value == null || value.isBlank()
                ? null : FinanceValidation.uuid(value, "id");
    }

    private LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getDate(column) == null
                ? null : rs.getDate(column).toLocalDate();
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(
                    value == null ? Map.of() : value
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Falha ao serializar histórico financeiro.",
                    exception
            );
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record LedgerFilter(
            String worksiteId,
            LocalDate from,
            LocalDate to,
            String type,
            String statusId,
            String supplierId,
            String responsibleId,
            String costCenterId,
            String categoryId,
            String query,
            LocalDate referenceDate,
            Integer limit,
            Integer offset
    ) {
        LedgerFilter normalized() {
            if (from != null && to != null && to.isBefore(from)) {
                throw FinanceValidation.badRequest("Período inválido.");
            }
            return new LedgerFilter(
                    FinanceValidation.uuid(worksiteId, "obraId"), from, to,
                    type == null || type.isBlank()
                            ? null
                            : FinanceInvoiceValidation.enumValue(
                                    type, "tipo", LEDGER_TYPES
                            ),
                    FinanceValidation.optionalUuid(statusId, "statusId"),
                    FinanceValidation.optionalUuid(
                            supplierId, "fornecedorId"
                    ),
                    FinanceValidation.optionalUuid(
                            responsibleId, "responsavelId"
                    ),
                    FinanceValidation.optionalUuid(
                            costCenterId, "centroCustoId"
                    ),
                    FinanceValidation.optionalUuid(categoryId, "categoriaId"),
                    FinanceValidation.optionalText(query, "query", 200),
                    referenceDate == null ? LocalDate.now() : referenceDate,
                    limit == null ? 100 : Math.min(200, Math.max(1, limit)),
                    offset == null ? 0 : Math.max(0, offset)
            );
        }
    }

    private record ValidatedAllocation(
            String id,
            String costCenterId,
            String categoryId,
            BigDecimal amount,
            BigDecimal percentage
    ) {
    }

    private record ValidatedBudgetLink(
            String id,
            String itemId,
            BigDecimal amount
    ) {
    }

    private record ValidatedLedger(
            String id,
            String mutationId,
            String worksiteId,
            String invoiceId,
            String supplierId,
            String costCenterId,
            String categoryId,
            String responsibleId,
            String statusId,
            String type,
            String origin,
            String documentNumber,
            String description,
            LocalDate competenceDate,
            LocalDate issueDate,
            LocalDate dueDate,
            String currency,
            BigDecimal original,
            BigDecimal discount,
            BigDecimal interest,
            BigDecimal fine,
            BigDecimal net,
            List<ValidatedAllocation> allocations,
            List<ValidatedBudgetLink> budgetLinks,
            Long baseVersion
    ) {
    }

    private record ValidatedSettlementAllocation(
            String id,
            String ledgerId,
            BigDecimal amount
    ) {
    }

    private record ValidatedSettlement(
            String id,
            String mutationId,
            String worksiteId,
            String type,
            String status,
            LocalDate date,
            String currency,
            BigDecimal total,
            String method,
            String bankReference,
            String notes,
            List<ValidatedSettlementAllocation> allocations,
            Long baseVersion
    ) {
    }

    private record LedgerSettlementTarget(
            String id,
            String worksiteId,
            String type,
            String currency,
            BigDecimal net,
            BigDecimal settled,
            BigDecimal applied
    ) {
    }
}

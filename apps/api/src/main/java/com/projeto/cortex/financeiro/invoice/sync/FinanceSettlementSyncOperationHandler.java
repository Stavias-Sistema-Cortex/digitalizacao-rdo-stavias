package com.projeto.cortex.financeiro.invoice.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos;
import com.projeto.cortex.financeiro.invoice.FinanceLedgerService;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("legacy-finance")
public class FinanceSettlementSyncOperationHandler
        implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_LIQUIDACAO",
            "ATUALIZAR_LIQUIDACAO",
            "CANCELAR_LIQUIDACAO"
    );

    private final FinanceLedgerService service;
    private final FinancialAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FinanceSettlementSyncOperationHandler(
            FinanceLedgerService service,
            FinancialAccessService access,
            JdbcTemplate jdbc,
            ObjectMapper mapper
    ) {
        this.service = service;
        this.access = access;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String entityType() {
        return "LIQUIDACAO";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_LIQUIDACAO".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        String id = FinanceSyncSupport.requireEntityId(mutation, "liquidação");
        FinanceAuditContext audit = FinanceSyncSupport.audit(mutation, context);
        JsonNode result;
        switch (mutation.operacao()) {
            case "CRIAR_LIQUIDACAO" -> {
                FinanceInvoiceDtos.SettlementRequest request = request(
                        mutation, id, null
                );
                requireOperate(request.obraId());
                result = mapper.valueToTree(
                        service.saveSettlement(request, audit)
                );
            }
            case "ATUALIZAR_LIQUIDACAO" -> {
                String worksite = worksite(id);
                FinanceInvoiceDtos.SettlementRequest request = request(
                        mutation, id, rowVersion(id)
                );
                FinanceSyncSupport.requireSameWorksite(
                        request.obraId(), worksite
                );
                requireOperate(worksite);
                result = mapper.valueToTree(
                        service.saveSettlement(request, audit)
                );
            }
            case "CANCELAR_LIQUIDACAO" -> {
                String worksite = worksite(id);
                requireOperate(worksite);
                FinanceInvoiceDtos.CancelSettlementRequest value =
                        FinanceSyncSupport.value(
                                mapper, mutation.payload(),
                                FinanceInvoiceDtos.CancelSettlementRequest.class
                        );
                service.cancelSettlement(
                        id,
                        new FinanceInvoiceDtos.CancelSettlementRequest(
                                mutation.clientMutationId(),
                                value.motivo(),
                                rowVersion(id)
                        ),
                        audit
                );
                ObjectNode cancelled = mapper.createObjectNode();
                cancelled.put("id", id);
                cancelled.put("status", "CANCELADA");
                result = cancelled;
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação de liquidação não suportada."
            );
        }
        return new AppliedSyncMutation(entityType(), id, result);
    }

    private FinanceInvoiceDtos.SettlementRequest request(
            SyncPushRequest.MutacaoCliente mutation,
            String id,
            Long rowVersion
    ) {
        FinanceInvoiceDtos.SettlementRequest value = FinanceSyncSupport.value(
                mapper, mutation.payload(),
                FinanceInvoiceDtos.SettlementRequest.class
        );
        FinanceSyncSupport.requireMatchingId(value.id(), id);
        return new FinanceInvoiceDtos.SettlementRequest(
                id, mutation.clientMutationId(), value.obraId(), value.tipo(),
                value.status(), value.dataLiquidacao(), value.moeda(),
                value.valorTotal(), value.meio(), value.referenciaBancaria(),
                value.observacoes(), value.lancamentos(), rowVersion
        );
    }

    private String worksite(String id) {
        return FinanceSyncSupport.settlementWorksite(jdbc, id);
    }

    private long rowVersion(String id) {
        return FinanceSyncSupport.rowVersion(
                jdbc, "finance_liquidacao", id, false, "Liquidação"
        );
    }

    private void requireOperate(String worksite) {
        access.requirePermission(
                worksite,
                FinancialPermission.FINANCEIRO_OPERAR
        );
    }
}

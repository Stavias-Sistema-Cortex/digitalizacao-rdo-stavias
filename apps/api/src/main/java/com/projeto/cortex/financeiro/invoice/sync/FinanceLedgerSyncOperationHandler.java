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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FinanceLedgerSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_LANCAMENTO",
            "ATUALIZAR_LANCAMENTO",
            "ARQUIVAR_LANCAMENTO"
    );

    private final FinanceLedgerService service;
    private final FinancialAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FinanceLedgerSyncOperationHandler(
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
        return "LANCAMENTO";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_LANCAMENTO".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        String id = FinanceSyncSupport.requireEntityId(mutation, "lançamento");
        FinanceAuditContext audit = FinanceSyncSupport.audit(mutation, context);
        JsonNode result;
        switch (mutation.operacao()) {
            case "CRIAR_LANCAMENTO" -> {
                FinanceInvoiceDtos.LedgerRequest request = request(
                        mutation, id, null
                );
                requireOperate(request.obraId());
                result = mapper.valueToTree(
                        service.saveLedger(request, audit)
                );
            }
            case "ATUALIZAR_LANCAMENTO" -> {
                String worksite = worksite(id);
                FinanceInvoiceDtos.LedgerRequest request = request(
                        mutation, id, rowVersion(id)
                );
                FinanceSyncSupport.requireSameWorksite(
                        request.obraId(), worksite
                );
                requireOperate(worksite);
                result = mapper.valueToTree(
                        service.saveLedger(request, audit)
                );
            }
            case "ARQUIVAR_LANCAMENTO" -> {
                String worksite = worksite(id);
                requireOperate(worksite);
                service.archiveLedger(
                        id, rowVersion(id), mutation.clientMutationId(), audit
                );
                ObjectNode archived = mapper.createObjectNode();
                archived.put("id", id);
                archived.put("arquivado", true);
                result = archived;
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação de lançamento não suportada."
            );
        }
        return new AppliedSyncMutation(entityType(), id, result);
    }

    private FinanceInvoiceDtos.LedgerRequest request(
            SyncPushRequest.MutacaoCliente mutation,
            String id,
            Long rowVersion
    ) {
        FinanceInvoiceDtos.LedgerRequest value = FinanceSyncSupport.value(
                mapper, mutation.payload(), FinanceInvoiceDtos.LedgerRequest.class
        );
        FinanceSyncSupport.requireMatchingId(value.id(), id);
        return new FinanceInvoiceDtos.LedgerRequest(
                id, mutation.clientMutationId(), value.obraId(),
                value.notaFiscalId(), value.fornecedorId(),
                value.centroCustoId(), value.categoriaId(),
                value.responsavelId(), value.statusId(), value.tipo(),
                value.origem(), value.numeroDocumento(), value.descricao(),
                value.dataCompetencia(), value.dataEmissao(),
                value.vencimentoEm(), value.moeda(), value.valorOriginal(),
                value.desconto(), value.juros(), value.multa(),
                value.valorLiquido(), value.alocacoes(),
                value.vinculosOrcamento(), rowVersion
        );
    }

    private String worksite(String id) {
        return FinanceSyncSupport.worksite(
                jdbc, "finance_lancamento", id, "Lançamento"
        );
    }

    private long rowVersion(String id) {
        return FinanceSyncSupport.rowVersion(
                jdbc, "finance_lancamento", id, true, "Lançamento"
        );
    }

    private void requireOperate(String worksite) {
        access.requirePermission(
                worksite,
                FinancialPermission.FINANCEIRO_OPERAR
        );
    }
}

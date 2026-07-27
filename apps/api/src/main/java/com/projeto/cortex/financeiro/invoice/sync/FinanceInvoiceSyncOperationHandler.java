package com.projeto.cortex.financeiro.invoice.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceService;
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
public class FinanceInvoiceSyncOperationHandler
        implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_NOTA_FISCAL",
            "ATUALIZAR_NOTA_FISCAL",
            "VINCULAR_DOCUMENTO_NOTA_FISCAL",
            "ARQUIVAR_NOTA_FISCAL"
    );

    private final FinanceInvoiceService service;
    private final FinancialAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FinanceInvoiceSyncOperationHandler(
            FinanceInvoiceService service,
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
        return "NOTA_FISCAL";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return Set.of("ATUALIZAR_NOTA_FISCAL", "ARQUIVAR_NOTA_FISCAL")
                .contains(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        String id = FinanceSyncSupport.requireEntityId(mutation, "nota fiscal");
        FinanceAuditContext audit = FinanceSyncSupport.audit(mutation, context);
        JsonNode result;
        switch (mutation.operacao()) {
            case "CRIAR_NOTA_FISCAL" -> {
                FinanceInvoiceDtos.InvoiceRequest request = request(
                        mutation, id, null
                );
                requireOperate(request.obraId());
                result = mapper.valueToTree(service.save(request, audit));
            }
            case "ATUALIZAR_NOTA_FISCAL" -> {
                String worksite = FinanceSyncSupport.worksite(
                        jdbc, "finance_nota_fiscal", id, "Nota fiscal"
                );
                FinanceInvoiceDtos.InvoiceRequest request = request(
                        mutation, id, FinanceSyncSupport.rowVersion(
                                jdbc, "finance_nota_fiscal", id, true,
                                "Nota fiscal"
                        )
                );
                FinanceSyncSupport.requireSameWorksite(
                        request.obraId(), worksite
                );
                requireOperate(worksite);
                result = mapper.valueToTree(service.save(request, audit));
            }
            case "VINCULAR_DOCUMENTO_NOTA_FISCAL" -> {
                String worksite = FinanceSyncSupport.worksite(
                        jdbc, "finance_nota_fiscal", id, "Nota fiscal"
                );
                requireOperate(worksite);
                FinanceInvoiceDtos.DocumentLinkRequest payload =
                        FinanceSyncSupport.value(
                                mapper, mutation.payload(),
                                FinanceInvoiceDtos.DocumentLinkRequest.class
                        );
                result = mapper.valueToTree(
                        service.linkDocument(id, payload, audit)
                );
            }
            case "ARQUIVAR_NOTA_FISCAL" -> {
                String worksite = FinanceSyncSupport.worksite(
                        jdbc, "finance_nota_fiscal", id, "Nota fiscal"
                );
                requireOperate(worksite);
                service.archive(
                        id,
                        FinanceSyncSupport.rowVersion(
                                jdbc, "finance_nota_fiscal", id, true,
                                "Nota fiscal"
                        ),
                        mutation.clientMutationId(),
                        audit
                );
                ObjectNode archived = mapper.createObjectNode();
                archived.put("id", id);
                archived.put("arquivado", true);
                result = archived;
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação de nota fiscal não suportada."
            );
        }
        return new AppliedSyncMutation(entityType(), id, result);
    }

    private FinanceInvoiceDtos.InvoiceRequest request(
            SyncPushRequest.MutacaoCliente mutation,
            String id,
            Long rowVersion
    ) {
        FinanceInvoiceDtos.InvoiceRequest value = FinanceSyncSupport.value(
                mapper, mutation.payload(), FinanceInvoiceDtos.InvoiceRequest.class
        );
        FinanceSyncSupport.requireMatchingId(value.id(), id);
        return new FinanceInvoiceDtos.InvoiceRequest(
                id, mutation.clientMutationId(), value.obraId(),
                value.fornecedorId(), value.centroCustoId(),
                value.categoriaId(), value.responsavelId(), value.statusId(),
                value.numero(), value.serie(), value.chaveAcesso(),
                value.tipoDocumento(), value.dataEmissao(),
                value.dataCompetencia(), value.dataRecebimento(),
                value.vencimentoEm(), value.moeda(), value.valorBruto(),
                value.desconto(), value.acrescimo(), value.retencoes(),
                value.valorLiquido(), value.observacoes(), value.compras(),
                rowVersion
        );
    }

    private void requireOperate(String worksite) {
        access.requirePermission(
                worksite,
                FinancialPermission.FINANCEIRO_OPERAR
        );
    }
}

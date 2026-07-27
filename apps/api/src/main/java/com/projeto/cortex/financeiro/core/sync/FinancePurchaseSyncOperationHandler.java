package com.projeto.cortex.financeiro.core.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceDtos;
import com.projeto.cortex.financeiro.core.FinancePurchaseService;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("legacy-finance")
public class FinancePurchaseSyncOperationHandler
        implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_COMPRA",
            "ATUALIZAR_COMPRA",
            "ALTERAR_STATUS_COMPRA",
            "DECIDIR_APROVACAO_COMPRA",
            "ARQUIVAR_COMPRA"
    );

    private final FinancePurchaseService service;
    private final FinancialAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FinancePurchaseSyncOperationHandler(
            FinancePurchaseService service,
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
        return "COMPRA";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_COMPRA".equals(operation)
                && !"DECIDIR_APROVACAO_COMPRA".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        String id = requireEntityId(mutation);
        FinanceAuditContext audit = audit(mutation, context);
        JsonNode result;
        switch (mutation.operacao()) {
            case "CRIAR_COMPRA" -> {
                FinanceDtos.PurchaseRequest request = withSyncMetadata(
                        value(mutation.payload(), FinanceDtos.PurchaseRequest.class),
                        mutation,
                        id,
                        null
                );
                access.requirePermission(
                        request.obraId(),
                        FinancialPermission.FINANCEIRO_OPERAR
                );
                result = mapper.valueToTree(service.savePurchase(request, audit));
            }
            case "ATUALIZAR_COMPRA" -> {
                FinanceDtos.PurchaseRequest request = withSyncMetadata(
                        value(mutation.payload(), FinanceDtos.PurchaseRequest.class),
                        mutation,
                        id,
                        rowVersion(id)
                );
                String storedWorksite = worksite(id);
                requireSameWorksite(request.obraId(), storedWorksite);
                access.requirePermission(
                        storedWorksite,
                        FinancialPermission.FINANCEIRO_OPERAR
                );
                result = mapper.valueToTree(service.savePurchase(request, audit));
            }
            case "ALTERAR_STATUS_COMPRA" -> {
                String storedWorksite = worksite(id);
                access.requirePermission(
                        storedWorksite,
                        FinancialPermission.FINANCEIRO_OPERAR
                );
                FinanceDtos.StatusChangeRequest payload = value(
                        mutation.payload(), FinanceDtos.StatusChangeRequest.class
                );
                result = mapper.valueToTree(service.changePurchaseStatus(
                        id,
                        new FinanceDtos.StatusChangeRequest(
                                payload.statusId(),
                                mutation.clientMutationId(),
                                rowVersion(id)
                        ),
                        audit
                ));
            }
            case "DECIDIR_APROVACAO_COMPRA" -> {
                String storedWorksite = worksite(id);
                access.requirePermission(
                        storedWorksite,
                        FinancialPermission.FINANCEIRO_APROVAR
                );
                FinanceDtos.ApprovalDecisionRequest payload = value(
                        mutation.payload(),
                        FinanceDtos.ApprovalDecisionRequest.class
                );
                result = mapper.valueToTree(service.decidePurchase(
                        id,
                        new FinanceDtos.ApprovalDecisionRequest(
                                payload.decisao(),
                                payload.justificativa(),
                                mutation.clientMutationId()
                        ),
                        audit
                ));
            }
            case "ARQUIVAR_COMPRA" -> {
                String storedWorksite = worksite(id);
                access.requirePermission(
                        storedWorksite,
                        FinancialPermission.FINANCEIRO_OPERAR
                );
                service.archivePurchase(
                        id,
                        rowVersion(id),
                        mutation.clientMutationId(),
                        audit
                );
                ObjectNode archived = mapper.createObjectNode();
                archived.put("id", id);
                archived.put("arquivado", true);
                result = archived;
            }
            default -> throw unsupported();
        }
        return new AppliedSyncMutation(entityType(), id, result);
    }

    private FinanceDtos.PurchaseRequest withSyncMetadata(
            FinanceDtos.PurchaseRequest request,
            SyncPushRequest.MutacaoCliente mutation,
            String id,
            Long rowVersion
    ) {
        requireMatchingId(request.id(), id);
        return new FinanceDtos.PurchaseRequest(
                id,
                mutation.clientMutationId(),
                request.solicitacaoId(),
                request.obraId(),
                request.centroCustoId(),
                request.categoriaId(),
                request.fornecedorId(),
                request.responsavelCompraId(),
                request.statusId(),
                request.numeroPedido(),
                request.descricao(),
                request.prioridade(),
                request.moeda(),
                request.valorPrevisto(),
                request.valorAprovado(),
                request.valorContratado(),
                request.valorPago(),
                request.pedidoEmitidoEm(),
                request.entregaPrevistaEm(),
                request.itens(),
                rowVersion
        );
    }

    private String worksite(String id) {
        try {
            return jdbc.queryForObject(
                    "SELECT obra_id FROM finance_compra WHERE id = ? AND arquivado_em IS NULL",
                    String.class,
                    id
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Compra não encontrada."
            );
        }
    }

    private long rowVersion(String id) {
        Long version = jdbc.queryForObject(
                "SELECT versao_linha FROM finance_compra WHERE id = ? AND arquivado_em IS NULL",
                Long.class,
                id
        );
        if (version == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Compra não encontrada."
            );
        }
        return version;
    }

    private void requireSameWorksite(String requestWorksite, String storedWorksite) {
        if (requestWorksite == null || !requestWorksite.equals(storedWorksite)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A obra da compra não pode ser alterada."
            );
        }
    }

    private void requireMatchingId(String requestId, String entityId) {
        if (requestId != null && !requestId.isBlank()
                && !entityId.equals(requestId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O id do payload difere do id da mutação."
            );
        }
    }

    private String requireEntityId(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.entidadeId() == null || mutation.entidadeId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeId da compra é obrigatório."
            );
        }
        return mutation.entidadeId().strip();
    }

    private FinanceAuditContext audit(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        String correlation = mutation.correlacaoId() == null
                || mutation.correlacaoId().isBlank()
                ? mutation.clientMutationId()
                : mutation.correlacaoId().strip();
        return new FinanceAuditContext(
                context.actorId(), context.deviceId(), correlation, "OFFLINE"
        );
    }

    private <T> T value(JsonNode payload, Class<T> type) {
        try {
            if (payload == null || payload.isNull()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "payload é obrigatório."
                );
            }
            return mapper.treeToValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "payload financeiro inválido."
            );
        }
    }

    private ResponseStatusException unsupported() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Operação de compra não suportada."
        );
    }
}

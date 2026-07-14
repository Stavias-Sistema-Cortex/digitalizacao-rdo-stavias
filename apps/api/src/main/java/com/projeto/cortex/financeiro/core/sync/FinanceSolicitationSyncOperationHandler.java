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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FinanceSolicitationSyncOperationHandler
        implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_SOLICITACAO_COMPRA",
            "ATUALIZAR_SOLICITACAO_COMPRA",
            "ARQUIVAR_SOLICITACAO_COMPRA"
    );

    private final FinancePurchaseService service;
    private final FinancialAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FinanceSolicitationSyncOperationHandler(
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
        return "SOLICITACAO_COMPRA";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_SOLICITACAO_COMPRA".equals(operation);
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
            case "CRIAR_SOLICITACAO_COMPRA" -> {
                FinanceDtos.SolicitationRequest request = withSyncMetadata(
                        value(mutation.payload(), FinanceDtos.SolicitationRequest.class),
                        mutation,
                        id,
                        null
                );
                access.requirePermission(
                        request.obraId(),
                        FinancialPermission.FINANCEIRO_OPERAR
                );
                result = mapper.valueToTree(
                        service.saveSolicitation(request, audit)
                );
            }
            case "ATUALIZAR_SOLICITACAO_COMPRA" -> {
                FinanceDtos.SolicitationRequest request = withSyncMetadata(
                        value(mutation.payload(), FinanceDtos.SolicitationRequest.class),
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
                result = mapper.valueToTree(
                        service.saveSolicitation(request, audit)
                );
            }
            case "ARQUIVAR_SOLICITACAO_COMPRA" -> {
                String storedWorksite = worksite(id);
                access.requirePermission(
                        storedWorksite,
                        FinancialPermission.FINANCEIRO_OPERAR
                );
                service.archiveSolicitation(
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

    private FinanceDtos.SolicitationRequest withSyncMetadata(
            FinanceDtos.SolicitationRequest request,
            SyncPushRequest.MutacaoCliente mutation,
            String id,
            Long rowVersion
    ) {
        requireMatchingId(request.id(), id);
        return new FinanceDtos.SolicitationRequest(
                id,
                mutation.clientMutationId(),
                request.obraId(),
                request.centroCustoId(),
                request.categoriaId(),
                request.fornecedorId(),
                request.solicitanteId(),
                request.responsavelCompraId(),
                request.statusId(),
                request.titulo(),
                request.descricao(),
                request.prioridade(),
                request.moeda(),
                request.valorPrevisto(),
                request.valorAprovado(),
                request.valorContratado(),
                request.valorPago(),
                request.necessarioEm(),
                request.itens(),
                rowVersion
        );
    }

    private String worksite(String id) {
        try {
            return jdbc.queryForObject(
                    "SELECT obra_id FROM finance_solicitacao_compra WHERE id = ? AND arquivado_em IS NULL",
                    String.class,
                    id
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Solicitação de compra não encontrada."
            );
        }
    }

    private long rowVersion(String id) {
        Long version = jdbc.queryForObject(
                "SELECT versao_linha FROM finance_solicitacao_compra WHERE id = ? AND arquivado_em IS NULL",
                Long.class,
                id
        );
        if (version == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Solicitação de compra não encontrada."
            );
        }
        return version;
    }

    private void requireSameWorksite(String requestWorksite, String storedWorksite) {
        if (requestWorksite == null || !requestWorksite.equals(storedWorksite)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A obra da solicitação não pode ser alterada."
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
                    "entidadeId da solicitação é obrigatório."
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
                "Operação de solicitação de compra não suportada."
        );
    }
}

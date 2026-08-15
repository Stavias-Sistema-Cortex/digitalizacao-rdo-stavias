package com.projeto.cortex.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionAudit;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionRequest;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoPrintableCollectionLimits;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RdoSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_RDO",
            "ATUALIZAR_RDO_RASCUNHO",
            "ENVIAR_RDO",
            "CANCELAR_RDO",
            "RESTAURAR_RDO",
            "DECIDIR_EXECUCAO_SERVICO_RDO"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RdoService rdoService;
    private final RdoDraftUpdateService rdoDraftUpdateService;
    private final RdoWorkflowService rdoWorkflowService;
    private final RdoQueryService rdoQueryService;
    private final CurrentUserService currentUserService;
    private final RdoExecutionDecisionService executionDecisionService;

    public RdoSyncOperationHandler(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RdoService rdoService,
            RdoDraftUpdateService rdoDraftUpdateService,
            RdoWorkflowService rdoWorkflowService,
            RdoQueryService rdoQueryService,
            CurrentUserService currentUserService,
            RdoExecutionDecisionService executionDecisionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rdoService = rdoService;
        this.rdoDraftUpdateService = rdoDraftUpdateService;
        this.rdoWorkflowService = rdoWorkflowService;
        this.rdoQueryService = rdoQueryService;
        this.currentUserService = currentUserService;
        this.executionDecisionService = executionDecisionService;
    }

    @Override
    public String entityType() {
        return "RDO";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_RDO".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        RdoResponse response = switch (mutation.operacao()) {
            case "CRIAR_RDO" -> create(mutation);
            case "ATUALIZAR_RDO_RASCUNHO" -> updateDraft(mutation);
            case "ENVIAR_RDO" -> send(mutation);
            case "CANCELAR_RDO" -> cancel(mutation);
            case "RESTAURAR_RDO" -> restore(mutation);
            case "DECIDIR_EXECUCAO_SERVICO_RDO" -> decideExecution(
                    mutation, context
            );
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação RDO não suportada."
            );
        };
        return new AppliedSyncMutation(
                entityType(),
                response.id(),
                objectMapper.valueToTree(response),
                null,
                "DECIDIR_EXECUCAO_SERVICO_RDO".equals(mutation.operacao())
        );
    }

    private RdoResponse create(SyncPushRequest.MutacaoCliente mutation) {
        ObjectNode payload = requireObjectPayload(mutation).deepCopy();
        payload.put("clientMutationId", mutation.clientMutationId());
        RdoCreateRequest request = toValue(payload, RdoCreateRequest.class);
        currentUserService.requireWorksiteAccess(request.obraId());
        RdoPrintableCollectionLimits.requireWithinTemplateCapacity(request);
        return rdoService.criarRascunho(request);
    }

    private RdoResponse updateDraft(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        RdoResponse persisted = rdoQueryService.buscarPorId(entityId);
        ObjectNode payload = requireObjectPayload(mutation).deepCopy();
        putNullable(payload, "previousRdoId", persisted.previousRdoId());
        putNullable(
                payload,
                "creationContextVersion",
                persisted.creationContextVersion()
        );
        putNullable(
                payload,
                "clientMutationId",
                persisted.clientMutationId()
        );
        RdoCreateRequest request = toValue(payload, RdoCreateRequest.class);
        currentUserService.requireWorksiteAccess(request.obraId());
        RdoPrintableCollectionLimits.requireWithinTemplateCapacity(request);
        return rdoDraftUpdateService.atualizarRascunho(
                entityId,
                request,
                mutation.baseVersao()
        );
    }

    private RdoResponse send(SyncPushRequest.MutacaoCliente mutation) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        return rdoWorkflowService.enviar(entityId);
    }

    /*
     * Apagar RDO — e desfazer o apagamento — é decisão de Alfa. O botão
     * "Apagar RDO" da lista é exatamente esta operação: o cancelamento
     * recuperável. Exigir só o acesso à obra deixava qualquer Beta vinculado
     * apagar o registro do dia inteiro da obra; e deixar a restauração aberta
     * permitiria ao Beta desfazer uma decisão que não é dele. As duas pontas
     * do ciclo pedem a mesma autoridade.
     *
     * A recusa chega ao aparelho como REJEITADA terminal (403), vai para a
     * revisão com o motivo escrito e não fica retentando para sempre.
     */
    private RdoResponse cancel(SyncPushRequest.MutacaoCliente mutation) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        currentUserService.requireAlfa();
        return rdoWorkflowService.cancelar(entityId);
    }

    private RdoResponse restore(SyncPushRequest.MutacaoCliente mutation) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        currentUserService.requireAlfa();
        return rdoWorkflowService.restaurar(entityId);
    }

    private RdoResponse decideExecution(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        String rdoId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(rdoId);
        if (context == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Contexto de sincronização é obrigatório para a decisão financeira."
            );
        }

        ObjectNode payload = requireObjectPayload(mutation);
        String executionId = requireText(payload, "executionId");
        RdoExecutionDecisionRequest request = new RdoExecutionDecisionRequest(
                optionalText(payload, "decisao"),
                optionalText(payload, "justificativa"),
                mutation.baseVersao(),
                mutation.clientMutationId()
        );
        return executionDecisionService.decidir(
                rdoId,
                executionId,
                request,
                new RdoExecutionDecisionAudit(
                        context.actorId(),
                        context.deviceId(),
                        mutation.trace().correlationId(),
                        "OFFLINE",
                        mutation.trace().causationId(),
                        mutation.trace().ontologyEventId(),
                        LocalDateTime.ofInstant(
                                Instant.parse(mutation.occurredAt()),
                                ZoneOffset.UTC
                        )
                )
        );
    }

    private ObjectNode requireObjectPayload(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.payload() instanceof ObjectNode objectPayload) {
            return objectPayload;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "payload de criação de RDO deve ser objeto."
        );
    }

    private String requireEntityId(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        if (mutation.entidadeId() == null
                || mutation.entidadeId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeId é obrigatório."
            );
        }
        return mutation.entidadeId().strip();
    }

    private String requireText(ObjectNode payload, String field) {
        String value = optionalText(payload, field);
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " é obrigatório."
            );
        }
        return value.strip();
    }

    private String optionalText(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " deve ser texto."
            );
        }
        return value.textValue();
    }

    private void putNullable(
            ObjectNode payload,
            String field,
            String value
    ) {
        if (value == null) {
            payload.putNull(field);
            return;
        }
        payload.put(field, value);
    }

    private void putNullable(
            ObjectNode payload,
            String field,
            Long value
    ) {
        if (value == null) {
            payload.putNull(field);
            return;
        }
        payload.put(field, value);
    }

    private <T> T toValue(JsonNode jsonNode, Class<T> type) {
        try {
            if (jsonNode == null || jsonNode.isNull()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "payload é obrigatório."
                );
            }
            return objectMapper.treeToValue(jsonNode, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "payload inválido para " + type.getSimpleName()
            );
        }
    }
}

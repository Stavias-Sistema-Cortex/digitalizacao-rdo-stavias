package com.projeto.cortex.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
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
            "ENVIAR_RDO"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RdoService rdoService;
    private final RdoDraftUpdateService rdoDraftUpdateService;
    private final RdoWorkflowService rdoWorkflowService;
    private final RdoQueryService rdoQueryService;
    private final CurrentUserService currentUserService;

    public RdoSyncOperationHandler(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RdoService rdoService,
            RdoDraftUpdateService rdoDraftUpdateService,
            RdoWorkflowService rdoWorkflowService,
            RdoQueryService rdoQueryService,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rdoService = rdoService;
        this.rdoDraftUpdateService = rdoDraftUpdateService;
        this.rdoWorkflowService = rdoWorkflowService;
        this.rdoQueryService = rdoQueryService;
        this.currentUserService = currentUserService;
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
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação RDO não suportada."
            );
        };
        return new AppliedSyncMutation(
                entityType(),
                response.id(),
                objectMapper.valueToTree(response)
        );
    }

    private RdoResponse create(SyncPushRequest.MutacaoCliente mutation) {
        RdoCreateRequest request = toValue(
                mutation.payload(),
                RdoCreateRequest.class
        );
        currentUserService.requireWorksiteAccess(request.obraId());
        if (request.id() != null && !request.id().isBlank()
                && rdoExists(request.id())) {
            currentUserService.requireRdoAccess(request.id());
            return rdoQueryService.buscarPorId(request.id());
        }
        return rdoService.criarRascunho(request);
    }

    private RdoResponse updateDraft(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        RdoCreateRequest request = toValue(
                mutation.payload(),
                RdoCreateRequest.class
        );
        currentUserService.requireWorksiteAccess(request.obraId());
        return rdoDraftUpdateService.atualizarRascunho(entityId, request);
    }

    private RdoResponse send(SyncPushRequest.MutacaoCliente mutation) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        return rdoWorkflowService.enviar(entityId);
    }

    private boolean rdoExists(String rdoId) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rdo WHERE id = ?",
                Integer.class,
                rdoId
        );
        return total != null && total > 0;
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

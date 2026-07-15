package com.projeto.cortex.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoDraftVersionConflictException;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
public final class RdoSyncMutationHandler implements SyncMutationHandler {

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

    public RdoSyncMutationHandler(
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
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_RDO".equals(operation);
    }

    @Override
    public SyncMutationApplied apply(SyncPushRequest.MutacaoCliente mutation) {
        RdoResponse response = switch (mutation.operacao()) {
            case "CRIAR_RDO" -> create(mutation);
            case "ATUALIZAR_RDO_RASCUNHO" -> updateDraft(mutation);
            case "ENVIAR_RDO" -> send(mutation);
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação RDO não suportada: " + mutation.operacao()
            );
        };

        long entityVersion = currentEntityVersion(response.id());
        long commitSeq = currentCommitSeq(response.id());
        ObjectNode result = objectMapper.valueToTree(response);
        result.put("versaoEntidade", entityVersion);

        return new SyncMutationApplied(
                "RDO",
                response.id(),
                entityVersion,
                commitSeq,
                result
        );
    }

    private RdoResponse create(SyncPushRequest.MutacaoCliente mutation) {
        RdoCreateRequest request = toValue(mutation.payload(), RdoCreateRequest.class);
        currentUserService.requireWorksiteAccess(request.obraId());

        if (request.id() != null && !request.id().isBlank() && rdoExists(request.id())) {
            currentUserService.requireRdoAccess(request.id());
            return rdoQueryService.buscarPorId(request.id());
        }

        return rdoService.criarRascunho(request);
    }

    private RdoResponse updateDraft(SyncPushRequest.MutacaoCliente mutation) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        RdoCreateRequest request = toValue(mutation.payload(), RdoCreateRequest.class);
        currentUserService.requireWorksiteAccess(request.obraId());
        try {
            return rdoDraftUpdateService.atualizarRascunho(
                    entityId,
                    request,
                    mutation.baseVersao()
            );
        } catch (RdoDraftVersionConflictException exception) {
            throw new SyncVersionConflictException(
                    "RDO",
                    entityId,
                    exception.expectedVersion(),
                    exception.currentVersion()
            );
        }
    }

    private RdoResponse send(SyncPushRequest.MutacaoCliente mutation) {
        String entityId = requireEntityId(mutation);
        currentUserService.requireRdoAccess(entityId);
        return rdoWorkflowService.enviar(entityId);
    }

    private long currentEntityVersion(String rdoId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT versao_entidade
                FROM cortex_estado_entidade
                WHERE tipo_entidade = ?
                  AND entidade_id = ?
                """,
                Long.class,
                "RDO",
                rdoId
        );

        return version == null ? 0 : version;
    }

    private long currentCommitSeq(String rdoId) {
        Long commitSeq = jdbcTemplate.queryForObject(
                """
                SELECT ev.commit_seq
                FROM cortex_estado_entidade e
                JOIN cortex_evento_operacional ev
                    ON ev.sequencia = e.ultimo_evento_seq
                WHERE e.tipo_entidade = ?
                  AND e.entidade_id = ?
                """,
                Long.class,
                "RDO",
                rdoId
        );

        if (commitSeq == null) {
            throw new IllegalStateException("Evento RDO não encontrado para o cursor de sync.");
        }
        return commitSeq;
    }

    private boolean rdoExists(String rdoId) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rdo WHERE id = ?",
                Integer.class,
                rdoId
        );
        return total != null && total > 0;
    }

    private String requireEntityId(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.entidadeId() == null || mutation.entidadeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entidadeId é obrigatório.");
        }
        return mutation.entidadeId();
    }

    private <T> T toValue(JsonNode payload, Class<T> type) {
        try {
            if (payload == null || payload.isNull()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload é obrigatório.");
            }
            return objectMapper.treeToValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "payload inválido para " + type.getSimpleName()
            );
        }
    }
}

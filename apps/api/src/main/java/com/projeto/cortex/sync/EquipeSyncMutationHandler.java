package com.projeto.cortex.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.equipes.EquipeArchiveRequest;
import com.projeto.cortex.equipes.EquipeCreateRequest;
import com.projeto.cortex.equipes.EquipeMemberEndRequest;
import com.projeto.cortex.equipes.EquipeMemberRequest;
import com.projeto.cortex.equipes.EquipeMemberResponse;
import com.projeto.cortex.equipes.EquipeResponse;
import com.projeto.cortex.equipes.EquipeService;
import com.projeto.cortex.equipes.EquipeUpdateRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
public final class EquipeSyncMutationHandler implements SyncMutationHandler {

    private static final String TEAM = "EQUIPE";
    private static final String MEMBERSHIP = "PARTICIPACAO_EQUIPE";
    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_EQUIPE",
            "ATUALIZAR_EQUIPE",
            "ARQUIVAR_EQUIPE",
            "ADICIONAR_MEMBRO_EQUIPE",
            "ATUALIZAR_MEMBRO_EQUIPE",
            "ENCERRAR_MEMBRO_EQUIPE"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EquipeService equipeService;

    public EquipeSyncMutationHandler(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EquipeService equipeService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.equipeService = equipeService;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_EQUIPE".equals(operation)
                && !"ADICIONAR_MEMBRO_EQUIPE".equals(operation);
    }

    @Override
    public SyncMutationApplied apply(SyncPushRequest.MutacaoCliente mutation) {
        AppliedDomainResult applied;
        try {
            applied = switch (mutation.operacao()) {
                case "CRIAR_EQUIPE" -> createTeam(mutation);
                case "ATUALIZAR_EQUIPE" -> updateTeam(mutation);
                case "ARQUIVAR_EQUIPE" -> archiveTeam(mutation);
                case "ADICIONAR_MEMBRO_EQUIPE" -> addMember(mutation);
                case "ATUALIZAR_MEMBRO_EQUIPE" -> updateMember(mutation);
                case "ENCERRAR_MEMBRO_EQUIPE" -> endMember(mutation);
                default -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Operação de equipe não suportada: " + mutation.operacao()
                );
            };
        } catch (ResponseStatusException exception) {
            if (!isVersionConflict(exception) || mutation.baseVersao() == null) {
                throw exception;
            }
            String entityId = requireEntityId(mutation);
            throw new SyncVersionConflictException(
                    mutation.entidadeTipo(),
                    entityId,
                    mutation.baseVersao(),
                    currentEntityVersion(mutation.entidadeTipo(), entityId)
            );
        }

        long entityVersion = currentEntityVersion(applied.entityType(), applied.entityId());
        long commitSeq = currentCommitSeq(applied.entityType(), applied.entityId());
        ObjectNode result = objectMapper.valueToTree(applied.response());
        result.put("versaoEntidade", entityVersion);
        return new SyncMutationApplied(
                applied.entityType(),
                applied.entityId(),
                entityVersion,
                commitSeq,
                result
        );
    }

    private AppliedDomainResult createTeam(SyncPushRequest.MutacaoCliente mutation) {
        requireEntityType(mutation, TEAM);
        EquipeCreateRequest request = toValue(payloadObject(mutation), EquipeCreateRequest.class);
        String stableId = requireStableCreateId(request.id(), "equipe");
        requireMatchingEntityId(mutation, stableId);
        EquipeResponse response = equipeService.criar(request);
        return new AppliedDomainResult(TEAM, response.id(), response);
    }

    private AppliedDomainResult updateTeam(SyncPushRequest.MutacaoCliente mutation) {
        requireEntityType(mutation, TEAM);
        String entityId = requireEntityId(mutation);
        EquipeUpdateRequest parsed = toValue(payloadObject(mutation), EquipeUpdateRequest.class);
        EquipeUpdateRequest request = new EquipeUpdateRequest(
                parsed.nome(),
                parsed.descricao(),
                parsed.status(),
                parsed.inicioValidadeEm(),
                parsed.fimValidadeEm(),
                mutation.baseVersao(),
                parsed.motivo()
        );
        EquipeResponse response = equipeService.atualizar(entityId, request);
        return new AppliedDomainResult(TEAM, response.id(), response);
    }

    private AppliedDomainResult archiveTeam(SyncPushRequest.MutacaoCliente mutation) {
        requireEntityType(mutation, TEAM);
        String entityId = requireEntityId(mutation);
        EquipeArchiveRequest parsed = toValue(payloadObject(mutation), EquipeArchiveRequest.class);
        EquipeResponse response = equipeService.arquivar(
                entityId,
                mutation.baseVersao(),
                parsed.motivo(),
                parsed.arquivadaEm()
        );
        return new AppliedDomainResult(TEAM, response.id(), response);
    }

    private AppliedDomainResult addMember(SyncPushRequest.MutacaoCliente mutation) {
        requireEntityType(mutation, MEMBERSHIP);
        ObjectNode payload = payloadObject(mutation);
        String teamId = requirePayloadText(payload, "equipeId");
        payload.remove("equipeId");
        EquipeMemberRequest request = toValue(payload, EquipeMemberRequest.class);
        String stableId = requireStableCreateId(request.id(), "participação na equipe");
        requireMatchingEntityId(mutation, stableId);
        EquipeMemberResponse response = equipeService.adicionarMembro(teamId, request);
        return new AppliedDomainResult(MEMBERSHIP, response.id(), response);
    }

    private AppliedDomainResult updateMember(SyncPushRequest.MutacaoCliente mutation) {
        requireEntityType(mutation, MEMBERSHIP);
        String entityId = requireEntityId(mutation);
        ObjectNode payload = payloadObject(mutation);
        String teamId = requirePayloadText(payload, "equipeId");
        payload.remove("equipeId");
        EquipeMemberRequest parsed = toValue(payload, EquipeMemberRequest.class);
        EquipeMemberRequest request = new EquipeMemberRequest(
                parsed.id(),
                parsed.colaboradorId(),
                parsed.funcaoOperacionalId(),
                parsed.responsavel(),
                parsed.inicioEm(),
                mutation.baseVersao(),
                parsed.motivo(),
                parsed.concederAcessoObra()
        );
        EquipeMemberResponse response = equipeService.atualizarMembro(
                teamId,
                entityId,
                request
        );
        return new AppliedDomainResult(MEMBERSHIP, response.id(), response);
    }

    private AppliedDomainResult endMember(SyncPushRequest.MutacaoCliente mutation) {
        requireEntityType(mutation, MEMBERSHIP);
        String entityId = requireEntityId(mutation);
        ObjectNode payload = payloadObject(mutation);
        String teamId = requirePayloadText(payload, "equipeId");
        payload.remove("equipeId");
        EquipeMemberEndRequest request = toValue(payload, EquipeMemberEndRequest.class);
        EquipeMemberResponse response = equipeService.encerrarMembro(
                teamId,
                entityId,
                mutation.baseVersao(),
                request.motivo(),
                request.encerradoEm()
        );
        return new AppliedDomainResult(MEMBERSHIP, response.id(), response);
    }

    private long currentEntityVersion(String entityType, String entityId) {
        try {
            Long version = jdbcTemplate.queryForObject(
                    """
                    SELECT versao_entidade
                    FROM cortex_estado_entidade
                    WHERE tipo_entidade = ?
                      AND entidade_id = ?
                    """,
                    Long.class,
                    entityType,
                    entityId
            );
            return version == null ? 0 : version;
        } catch (EmptyResultDataAccessException exception) {
            return 0;
        }
    }

    private long currentCommitSeq(String entityType, String entityId) {
        Long commitSeq = jdbcTemplate.queryForObject(
                """
                SELECT ev.commit_seq
                FROM cortex_estado_entidade state
                JOIN cortex_evento_operacional ev
                    ON ev.sequencia = state.ultimo_evento_seq
                WHERE state.tipo_entidade = ?
                  AND state.entidade_id = ?
                """,
                Long.class,
                entityType,
                entityId
        );
        if (commitSeq == null) {
            throw new IllegalStateException(
                    "Evento de equipe não encontrado para o cursor de sync."
            );
        }
        return commitSeq;
    }

    private ObjectNode payloadObject(SyncPushRequest.MutacaoCliente mutation) {
        JsonNode payload = mutation.payload();
        if (payload == null || !payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload deve ser um objeto.");
        }
        return ((ObjectNode) payload).deepCopy();
    }

    private <T> T toValue(JsonNode payload, Class<T> type) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "payload inválido para " + type.getSimpleName()
            );
        }
    }

    private void requireEntityType(
            SyncPushRequest.MutacaoCliente mutation,
            String expected
    ) {
        if (!expected.equals(mutation.entidadeTipo())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeTipo precisa ser " + expected + " para " + mutation.operacao() + "."
            );
        }
    }

    private String requireEntityId(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.entidadeId() == null || mutation.entidadeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entidadeId é obrigatório.");
        }
        return mutation.entidadeId().trim();
    }

    private String requireStableCreateId(String value, String entityName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O ID da " + entityName + " é obrigatório para criação offline idempotente."
            );
        }
        return value.trim();
    }

    private void requireMatchingEntityId(
            SyncPushRequest.MutacaoCliente mutation,
            String createdId
    ) {
        String mutationId = requireEntityId(mutation);
        if (!mutationId.equals(createdId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeId não corresponde ao ID informado no payload."
            );
        }
    }

    private String requirePayloadText(ObjectNode payload, String field) {
        String value = payload.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " é obrigatório no payload."
            );
        }
        return value;
    }

    private boolean isVersionConflict(ResponseStatusException exception) {
        return exception.getStatusCode().value() == HttpStatus.CONFLICT.value()
                && exception.getReason() != null
                && exception.getReason().startsWith("Conflito de versão");
    }

    private record AppliedDomainResult(
            String entityType,
            String entityId,
            Object response
    ) {
    }
}

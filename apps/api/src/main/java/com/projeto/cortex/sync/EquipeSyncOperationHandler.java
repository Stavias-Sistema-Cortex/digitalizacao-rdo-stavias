package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.equipes.EquipeArchiveRequest;
import com.projeto.cortex.equipes.EquipeCreateRequest;
import com.projeto.cortex.equipes.EquipeMemberEndRequest;
import com.projeto.cortex.equipes.EquipeMemberRequest;
import com.projeto.cortex.equipes.EquipeResponse;
import com.projeto.cortex.equipes.EquipeService;
import com.projeto.cortex.equipes.EquipeUnarchiveRequest;
import com.projeto.cortex.equipes.EquipeUpdateRequest;
import com.projeto.cortex.equipes.EquipeWorksiteEndRequest;
import com.projeto.cortex.equipes.EquipeWorksiteRequest;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Canonical v13 adapter for Alfa team administration. */
@Component
public class EquipeSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_EQUIPE",
            "ATUALIZAR_EQUIPE",
            "ARQUIVAR_EQUIPE",
            "DESARQUIVAR_EQUIPE",
            "ALTERAR_VINCULO_EQUIPE"
    );

    private final EquipeService service;
    private final CortexOperationalMemoryService memoryService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public EquipeSyncOperationHandler(
            EquipeService service,
            CortexOperationalMemoryService memoryService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.memoryService = memoryService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityType() {
        return "EQUIPE";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_EQUIPE".equals(operation);
    }

    /*
     * Este handler lê `entityId()` e `baseVersion()` — os apelidos que só o
     * envelope canônico traz. Num envelope legado os dois chegam nulos, e o
     * primeiro estourava dentro de `requireText`.
     */
    @Override
    public boolean requiresCanonicalEnvelope() {
        return true;
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        currentUserService.requireAdmin();
        ObjectNode payload = payload(mutation);
        String teamId = mutation.entityId();
        requireText(payload, "id", teamId);

        EquipeResponse result;
        String eventType;
        switch (mutation.operacao()) {
            case "CRIAR_EQUIPE" -> {
                EquipeCreateRequest request = new EquipeCreateRequest(
                        teamId,
                        requiredText(payload, "obraId"),
                        requiredText(payload, "nome"),
                        nullableText(payload, "descricao"),
                        objectMapper.convertValue(
                                payload.get("inicioValidadeEm"),
                                java.time.LocalDateTime.class
                        )
                );
                result = service.criar(request);
                eventType = "EQUIPE_CRIADA";
            }
            case "ATUALIZAR_EQUIPE" -> {
                EquipeUpdateRequest request = new EquipeUpdateRequest(
                        requiredText(payload, "nome"),
                        nullableText(payload, "descricao"),
                        requiredText(payload, "status"),
                        objectMapper.convertValue(
                                payload.get("inicioValidadeEm"),
                                java.time.LocalDateTime.class
                        ),
                        nullableDateTime(payload, "fimValidadeEm"),
                        mutation.baseVersion(),
                        requiredText(payload, "motivo")
                );
                result = service.atualizar(teamId, request);
                eventType = "EQUIPE_ATUALIZADA";
            }
            case "ARQUIVAR_EQUIPE" -> {
                // Sem motivo obrigatório: o arquivamento se explica sozinho.
                EquipeArchiveRequest request = new EquipeArchiveRequest(
                        mutation.baseVersion(),
                        nullableText(payload, "motivo"),
                        nullableDateTime(payload, "arquivadaEm")
                );
                result = service.arquivar(
                        teamId,
                        request.baseVersao(),
                        request.motivo(),
                        request.arquivadaEm()
                );
                eventType = "EQUIPE_ARQUIVADA";
            }
            case "DESARQUIVAR_EQUIPE" -> {
                EquipeUnarchiveRequest request = new EquipeUnarchiveRequest(
                        mutation.baseVersion(),
                        nullableDateTime(payload, "desarquivadaEm")
                );
                result = service.desarquivar(
                        teamId,
                        request.baseVersao(),
                        request.desarquivadaEm()
                );
                eventType = "EQUIPE_DESARQUIVADA";
            }
            case "ALTERAR_VINCULO_EQUIPE" -> {
                applyLinkChange(teamId, payload);
                result = service.buscarPorId(teamId);
                eventType = "EQUIPE_VINCULO_ALTERADO";
                publishAggregateLinkEvent(result, payload, context);
            }
            default -> throw badRequest("Operação de equipe não suportada.");
        }

        ObjectNode snapshot = objectMapper.valueToTree(result);
        return new AppliedSyncMutation(
                entityType(),
                teamId,
                snapshot,
                new AppliedSyncMutation.AuthoritativeEvent(
                        eventType,
                        List.of(new AppliedSyncMutation.AuthoritativeRelatedEntity(
                                "OBRA",
                                result.obraPrincipalId()
                        ))
                )
        );
    }

    private void applyLinkChange(String teamId, ObjectNode payload) {
        String action = requiredText(payload, "vinculoAcao");
        switch (action) {
            case "ADICIONAR_MEMBRO" -> service.adicionarMembro(
                    teamId,
                    objectMapper.convertValue(
                            payload.get("vinculo"),
                            EquipeMemberRequest.class
                    )
            );
            case "ATUALIZAR_MEMBRO" -> {
                EquipeMemberRequest request = objectMapper.convertValue(
                        payload.get("vinculo"),
                        EquipeMemberRequest.class
                );
                service.atualizarMembro(teamId, request.id(), request);
            }
            case "ENCERRAR_MEMBRO" -> {
                String linkId = requiredText(payload, "vinculoId");
                EquipeMemberEndRequest request = objectMapper.convertValue(
                        payload.get("vinculo"),
                        EquipeMemberEndRequest.class
                );
                service.encerrarMembro(
                        teamId,
                        linkId,
                        request.baseVersao(),
                        request.motivo(),
                        request.encerradoEm()
                );
            }
            case "ADICIONAR_OBRA" -> service.adicionarObra(
                    teamId,
                    objectMapper.convertValue(
                            payload.get("vinculo"),
                            EquipeWorksiteRequest.class
                    )
            );
            case "ENCERRAR_OBRA" -> {
                String linkId = requiredText(payload, "vinculoId");
                EquipeWorksiteEndRequest request = objectMapper.convertValue(
                        payload.get("vinculo"),
                        EquipeWorksiteEndRequest.class
                );
                service.encerrarObra(
                        teamId,
                        linkId,
                        request.baseVersao(),
                        request.motivo(),
                        request.encerradoEm()
                );
            }
            default -> throw badRequest("Alteração de vínculo de equipe inválida.");
        }
    }

    private void publishAggregateLinkEvent(
            EquipeResponse team,
            ObjectNode payload,
            SyncMutationContext context
    ) {
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("schemaVersion", 13);
        eventPayload.put("teamId", team.id());
        eventPayload.put("worksiteId", team.obraPrincipalId());
        eventPayload.put("action", requiredText(payload, "vinculoAcao"));
        eventPayload.put("actorId", context.actorId());
        eventPayload.put("deviceId", context.deviceId());
        memoryService.registrarEvento(
                entityType(),
                team.id(),
                "EQUIPE_VINCULO_ALTERADO",
                "SYNC",
                team.obraPrincipalId(),
                eventPayload
        );
    }

    private ObjectNode payload(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.payload() == null || !mutation.payload().isObject()) {
            throw badRequest("Payload de equipe inválido.");
        }
        return (ObjectNode) mutation.payload();
    }

    private void requireText(ObjectNode payload, String field, String expected) {
        if (!expected.equals(requiredText(payload, field))) {
            throw badRequest(field + " diverge do envelope canônico.");
        }
    }

    private String requiredText(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        return value.textValue().strip();
    }

    private String nullableText(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private java.time.LocalDateTime nullableDateTime(
            ObjectNode payload,
            String field
    ) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull()
                ? null
                : objectMapper.convertValue(
                        value,
                        java.time.LocalDateTime.class
                );
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

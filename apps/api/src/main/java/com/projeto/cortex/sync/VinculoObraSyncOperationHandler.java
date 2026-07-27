package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.VinculoColaboradorObraResponse;
import com.projeto.cortex.obras.VinculoColaboradorObraService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Canonical adapter for explicit collaborator-to-worksite governance links. */
@Component
public class VinculoObraSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "VINCULAR_COLABORADOR_OBRA",
            "REVOGAR_VINCULO_COLABORADOR_OBRA"
    );

    private final VinculoColaboradorObraService service;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public VinculoObraSyncOperationHandler(
            VinculoColaboradorObraService service,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityType() {
        return "VINCULO_OBRA";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return "REVOGAR_VINCULO_COLABORADOR_OBRA".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        currentUserService.requireAdmin();
        ObjectNode payload = payload(mutation);
        requireText(payload, "id", mutation.entityId());
        requireText(payload, "obraId", mutation.obraId());
        String collaboratorId = requiredText(payload, "colaboradorId");

        VinculoColaboradorObraResponse result;
        String eventType;
        if ("VINCULAR_COLABORADOR_OBRA".equals(mutation.operacao())) {
            result = service.vincularComId(
                    mutation.entityId(),
                    mutation.obraId(),
                    collaboratorId,
                    nullableText(payload, "papelNaObra"),
                    context.actorId()
            );
            eventType = "VINCULO_OBRA_ATRIBUIDO";
        } else if (
            "REVOGAR_VINCULO_COLABORADOR_OBRA".equals(mutation.operacao())
        ) {
            result = service.revogar(
                    mutation.obraId(),
                    collaboratorId,
                    context.actorId()
            );
            eventType = "VINCULO_OBRA_REVOGADO";
        } else {
            throw badRequest("Operação de vínculo com obra não suportada.");
        }

        if (!mutation.entityId().equals(result.id())) {
            throw new IllegalStateException(
                    "O serviço de vínculo alterou a identidade canônica."
            );
        }
        JsonNode snapshot = objectMapper.valueToTree(result);
        return new AppliedSyncMutation(
                entityType(),
                mutation.entityId(),
                snapshot,
                new AppliedSyncMutation.AuthoritativeEvent(
                        eventType,
                        List.of(
                                new AppliedSyncMutation.AuthoritativeRelatedEntity(
                                        "OBRA",
                                        mutation.obraId()
                                ),
                                new AppliedSyncMutation.AuthoritativeRelatedEntity(
                                        "COLABORADOR",
                                        collaboratorId
                                )
                        )
                )
        );
    }

    private ObjectNode payload(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.payload() == null || !mutation.payload().isObject()) {
            throw badRequest("Payload de vínculo com obra inválido.");
        }
        return (ObjectNode) mutation.payload();
    }

    private void requireText(ObjectNode payload, String field, String expected) {
        if (expected == null || !expected.equals(requiredText(payload, field))) {
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

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.ObraResponse;
import com.projeto.cortex.obras.ObraService;
import com.projeto.cortex.obras.ObraUpdateRequest;
import com.projeto.cortex.obras.ObraVersionRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Canonical v13 adapter for the Alfa worksite lifecycle. */
@Component
public class ObraSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "ATUALIZAR_OBRA",
            "DESATIVAR_OBRA",
            "ATIVAR_OBRA",
            "ARQUIVAR_OBRA",
            "RESTAURAR_OBRA"
    );

    private final ObraService service;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public ObraSyncOperationHandler(
            ObraService service,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityType() {
        return "OBRA";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return OPERATIONS.contains(operation);
    }

    /*
     * O ciclo de vida da obra já era canônico-só, mas por uma lista de
     * operações mantida em `SyncService`. Declarar aqui põe a exigência ao lado
     * do `entityId()` que a torna necessária, em vez de depender de alguém
     * lembrar de editar a lista distante ao acrescentar uma operação.
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
        ObjectNode payload = requirePayload(mutation);
        String obraId = mutation.entityId();
        requireIdentity(payload, "id", obraId);
        requireIdentity(payload, "obraId", obraId);
        long baseVersion = requireBaseVersion(mutation);

        currentUserService.requireAlfa();

        ObraResponse response;
        String eventType;
        switch (mutation.operacao()) {
            case "ATUALIZAR_OBRA" -> {
                response = service.atualizarObra(
                        obraId,
                        updateRequest(payload, baseVersion),
                        context.actorId()
                );
                eventType = "OBRA_ATUALIZADA";
            }
            case "DESATIVAR_OBRA" -> {
                response = service.desativarObra(
                        obraId,
                        new ObraVersionRequest(baseVersion),
                        context.actorId()
                );
                eventType = "OBRA_DESATIVADA";
            }
            case "ATIVAR_OBRA" -> {
                response = service.ativarObra(
                        obraId,
                        new ObraVersionRequest(baseVersion),
                        context.actorId()
                );
                eventType = "OBRA_ATIVADA";
            }
            case "ARQUIVAR_OBRA" -> {
                response = service.arquivarObra(
                        obraId,
                        new ObraVersionRequest(baseVersion),
                        context.actorId()
                );
                eventType = "OBRA_ARQUIVADA";
            }
            case "RESTAURAR_OBRA" -> {
                response = service.restaurarObra(
                        obraId,
                        new ObraVersionRequest(baseVersion),
                        context.actorId()
                );
                eventType = "OBRA_RESTAURADA";
            }
            default -> throw badRequest("Operação de obra não suportada.");
        }

        return new AppliedSyncMutation(
                entityType(),
                obraId,
                objectMapper.valueToTree(response),
                new AppliedSyncMutation.AuthoritativeEvent(
                        eventType,
                        List.of()
                )
        );
    }

    private ObjectNode requirePayload(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        if (mutation == null
                || mutation.payload() == null
                || !mutation.payload().isObject()) {
            throw badRequest("Payload de obra inválido.");
        }
        return (ObjectNode) mutation.payload();
    }

    private void requireIdentity(
            ObjectNode payload,
            String field,
            String expected
    ) {
        JsonNode value = payload.get(field);
        if (expected == null
                || expected.isBlank()
                || value == null
                || !value.isTextual()
                || !expected.equals(value.textValue())) {
            throw badRequest(
                    "payload." + field + " diverge do envelope canônico."
            );
        }
    }

    private long requireBaseVersion(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        Long baseVersion = mutation.baseVersion();
        if (baseVersion == null || baseVersion < 0) {
            throw badRequest(
                    "baseVersion é obrigatório e não pode ser negativo."
            );
        }
        return baseVersion;
    }

    private ObraUpdateRequest updateRequest(
            ObjectNode payload,
            long baseVersion
    ) {
        return new ObraUpdateRequest(
                text(payload, "codigoContrato"),
                text(payload, "codigoInterno"),
                text(payload, "nome"),
                text(payload, "cliente"),
                text(payload, "descricao"),
                text(payload, "cidade"),
                text(payload, "uf"),
                text(payload, "rodovia"),
                text(payload, "fonteArquivo"),
                text(payload, "observacoes"),
                baseVersion
        );
    }

    private String text(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw badRequest("payload." + field + " deve ser texto.");
        }
        return value.textValue();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

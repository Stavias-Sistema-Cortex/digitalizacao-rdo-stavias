package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.integracoes.IntegracaoActionResponse;
import com.projeto.cortex.integracoes.IntegracaoAdminService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Executes a previously persisted external-integration request.
 *
 * <p>No provider is contacted by the browser fallback. The sync transaction
 * reaches this handler only after reconnecting, where Alfa authorization is
 * revalidated immediately before the real provider call.
 */
@Component
public class IntegracaoSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS =
            Set.of("SOLICITAR_INTEGRACAO");

    private final IntegracaoAdminService service;
    private final CortexOperationalMemoryService memoryService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final boolean importEnabled;

    public IntegracaoSyncOperationHandler(
            IntegracaoAdminService service,
            CortexOperationalMemoryService memoryService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            @Value("${cortex.import.enabled:false}") boolean importEnabled
    ) {
        this.service = service;
        this.memoryService = memoryService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.importEnabled = importEnabled;
    }

    @Override
    public String entityType() {
        return "SOLICITACAO_INTEGRACAO";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return false;
    }

    /*
     * A solicitação de integração é identificada pelo `entityId()` do envelope,
     * do começo ao fim: confere o payload, nomeia o recibo e vira evidência na
     * memória operacional. Sem o apelido canônico não há o que executar.
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
        ObjectNode payload = requirePayload(mutation);
        requireText(payload, "id", mutation.entityId());
        requireText(payload, "estado", "PENDENTE");
        requireText(payload, "motivo", "AGUARDANDO_REDE");
        String integrationId = requireIntegration(payload);
        String action = requireAction(payload);

        IntegracaoActionResponse response;
        String eventType;
        if ("TESTAR".equals(action)) {
            response = service.testConnection(integrationId);
            eventType = "INTEGRACAO_TESTADA";
        } else {
            if (!importEnabled) {
                response = new IntegracaoActionResponse(
                        integrationId,
                        "DISABLED",
                        "Sincronização manual desativada neste ambiente."
                );
            } else {
                response = service.startSync(integrationId);
            }
            eventType = "INTEGRACAO_SINCRONIZADA";
        }

        ObjectNode result = objectMapper.createObjectNode()
                .put("id", mutation.entityId())
                .put("integracaoId", integrationId)
                .put("acao", action)
                .put("estado", response.status())
                .put("mensagem", safeMessage(response))
                .put("executedAt", Instant.now().toString());
        Map<String, Object> memoryPayload = new LinkedHashMap<>();
        memoryPayload.put("schemaVersion", 13);
        memoryPayload.put("requestId", mutation.entityId());
        memoryPayload.put("integrationId", integrationId);
        memoryPayload.put("action", action);
        memoryPayload.put("status", response.status());
        memoryPayload.put("message", safeMessage(response));
        memoryPayload.put("actorId", context.actorId());
        memoryPayload.put("deviceId", context.deviceId());
        memoryService.registrarEvento(
                entityType(),
                mutation.entityId(),
                eventType,
                "SYNC",
                null,
                memoryPayload
        );

        return new AppliedSyncMutation(
                entityType(),
                mutation.entityId(),
                result,
                new AppliedSyncMutation.AuthoritativeEvent(
                        eventType,
                        List.of()
                )
        );
    }

    private ObjectNode requirePayload(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        JsonNode payload = mutation.payload();
        if (!"SOLICITAR_INTEGRACAO".equals(mutation.operacao())
                || payload == null
                || !payload.isObject()) {
            throw badRequest("Solicitação de integração inválida.");
        }
        return (ObjectNode) payload;
    }

    private String requireIntegration(ObjectNode payload) {
        String integrationId = text(payload, "integracaoId");
        if (!Set.of("academy", "zeladoria").contains(integrationId)) {
            throw badRequest("Integração desconhecida.");
        }
        return integrationId;
    }

    private String requireAction(ObjectNode payload) {
        String action = text(payload, "acao");
        if (!Set.of("TESTAR", "SINCRONIZAR").contains(action)) {
            throw badRequest("Ação de integração inválida.");
        }
        return action;
    }

    private String safeMessage(IntegracaoActionResponse response) {
        if ("FAILED".equals(response.status())) {
            return "A integração falhou. Consulte o relatório autorizado para detalhes.";
        }
        return response.mensagem();
    }

    private void requireText(
            ObjectNode payload,
            String field,
            String expected
    ) {
        if (!expected.equals(text(payload, field))) {
            throw badRequest(field + " diverge do envelope canônico.");
        }
    }

    private String text(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        return value.textValue().strip();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

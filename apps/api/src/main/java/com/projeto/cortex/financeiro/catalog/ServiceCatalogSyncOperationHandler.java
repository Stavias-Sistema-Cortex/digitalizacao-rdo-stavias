package com.projeto.cortex.financeiro.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ServiceCatalogSyncOperationHandler implements SyncOperationHandler {

    private static final String OPERATION = "CRIAR_SERVICO_CATALOGO";

    private final ServicePriceCatalogService service;
    private final FinancialAccessService access;
    private final ObjectMapper mapper;

    public ServiceCatalogSyncOperationHandler(
            ServicePriceCatalogService service,
            FinancialAccessService access,
            ObjectMapper mapper
    ) {
        this.service = service;
        this.access = access;
        this.mapper = mapper;
    }

    @Override
    public String entityType() {
        return "SERVICE";
    }

    @Override
    public Set<String> operations() {
        return Set.of(OPERATION);
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return false;
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        requireOperation(mutation);
        JsonNode payload = requirePayload(mutation.payload());
        String entityId = envelopeEntityId(mutation);
        String worksiteId = matchingUuid(
                payload, "obraId", mutation.obraId(), "payload.obraId"
        );
        matchingUuid(payload, "id", entityId, "payload.id");
        access.requirePermission(
                worksiteId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        ServiceCatalogEntry created = service.createService(
                worksiteId,
                context.actorId(),
                new CreateServiceCommand(
                        entityId,
                        mutation.clientMutationId(),
                        text(payload, "code", true),
                        text(payload, "name", true),
                        text(payload, "description", false)
                )
        );
        requireAppliedId(created.id(), entityId);
        return new AppliedSyncMutation(
                entityType(),
                entityId,
                mapper.valueToTree(created)
        );
    }

    private void requireOperation(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation == null
                || !OPERATION.equals(mutation.operacao())
                || !entityType().equals(mutation.entidadeTipo())) {
            throw badRequest("Operação de catálogo de serviço não suportada.");
        }
    }

    private String envelopeEntityId(SyncPushRequest.MutacaoCliente mutation) {
        String id = FinanceValidation.uuid(mutation.entidadeId(), "entidadeId");
        if (mutation.entityId() != null
                && !id.equals(FinanceValidation.uuid(mutation.entityId(), "entityId"))) {
            throw badRequest("entityId diverge do envelope.");
        }
        return id;
    }

    private String matchingUuid(
            JsonNode payload,
            String field,
            String expected,
            String label
    ) {
        String value = FinanceValidation.uuid(text(payload, field, true), label);
        String normalizedExpected = FinanceValidation.uuid(expected, label);
        if (!normalizedExpected.equals(value)) {
            throw badRequest(label + " diverge do envelope.");
        }
        return value;
    }

    private JsonNode requirePayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw badRequest("payload do catálogo de serviço é obrigatório.");
        }
        return payload;
    }

    private String text(JsonNode payload, String field, boolean required) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw badRequest(field + " é obrigatório.");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw badRequest(field + " deve ser textual.");
        }
        return value.textValue();
    }

    private void requireAppliedId(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Catálogo persistiu identidade diferente do envelope."
            );
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

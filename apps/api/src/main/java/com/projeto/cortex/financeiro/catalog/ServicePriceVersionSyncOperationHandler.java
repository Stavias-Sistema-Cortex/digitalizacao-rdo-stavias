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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ServicePriceVersionSyncOperationHandler
        implements SyncOperationHandler {

    private static final String CREATE = "CRIAR_PRECO_SERVICO";
    private static final String SUPERSEDE = "SUBSTITUIR_PRECO_SERVICO";
    private static final String CANCEL = "CANCELAR_PRECO_SERVICO";
    private static final Set<String> OPERATIONS = Set.of(
            CREATE, SUPERSEDE, CANCEL
    );

    private final ServicePriceCatalogService service;
    private final FinancialAccessService access;
    private final ObjectMapper mapper;

    public ServicePriceVersionSyncOperationHandler(
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
        return "SERVICE_PRICE_VERSION";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return CANCEL.equals(operation);
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

        ServicePriceVersion result = switch (mutation.operacao()) {
            case CREATE -> create(mutation, context, payload, worksiteId, entityId);
            case SUPERSEDE -> supersede(
                    mutation, context, payload, worksiteId, entityId
            );
            case CANCEL -> cancel(mutation, context, payload, worksiteId, entityId);
            default -> throw badRequest("Operação de preço de serviço não suportada.");
        };
        requireAppliedId(result.id(), entityId);
        return new AppliedSyncMutation(
                entityType(),
                entityId,
                mapper.valueToTree(result),
                authoritativeEvent(mutation.operacao(), result)
        );
    }

    private AppliedSyncMutation.AuthoritativeEvent authoritativeEvent(
            String operation,
            ServicePriceVersion result
    ) {
        List<AppliedSyncMutation.AuthoritativeRelatedEntity> related =
                new ArrayList<>();
        related.add(new AppliedSyncMutation.AuthoritativeRelatedEntity(
                "SERVICE", result.serviceId()
        ));
        related.add(new AppliedSyncMutation.AuthoritativeRelatedEntity(
                "WORKSITE", result.obraId()
        ));
        if (SUPERSEDE.equals(operation)) {
            if (result.supersedesId() == null) {
                throw new IllegalStateException(
                        "A substituição aplicada não informou a versão anterior."
                );
            }
            related.add(new AppliedSyncMutation.AuthoritativeRelatedEntity(
                    "SERVICE_PRICE_VERSION", result.supersedesId()
            ));
        }
        return new AppliedSyncMutation.AuthoritativeEvent(
                CANCEL.equals(operation)
                        ? "SERVICE_PRICE_VERSION_CANCELLED"
                        : "SERVICE_PRICE_VERSION_PUBLISHED",
                related
        );
    }

    private ServicePriceVersion create(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context,
            JsonNode payload,
            String worksiteId,
            String entityId
    ) {
        String serviceId = FinanceValidation.uuid(
                text(payload, "serviceId", true), "serviceId"
        );
        requireExactRelated(
                mutation.relatedEntities(),
                "SERVICE",
                serviceId,
                "serviceId"
        );
        return service.createPrice(
                worksiteId,
                context.actorId(),
                serviceId,
                new CreateServicePriceCommand(
                        entityId,
                        mutation.clientMutationId(),
                        text(payload, "unit", true),
                        text(payload, "currency", true),
                        decimal(payload, "unitPrice"),
                        date(payload, "validFrom", true),
                        date(payload, "validTo", false),
                        text(payload, "source", true)
                )
        );
    }

    private ServicePriceVersion supersede(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context,
            JsonNode payload,
            String worksiteId,
            String entityId
    ) {
        String previousId = FinanceValidation.uuid(
                text(payload, "previousPriceId", true), "previousPriceId"
        );
        requireExactRelated(
                mutation.relatedEntities(),
                "SERVICE_PRICE_VERSION",
                previousId,
                "previousPriceId"
        );
        return service.supersedePrice(
                worksiteId,
                context.actorId(),
                previousId,
                new SupersedeServicePriceCommand(
                        entityId,
                        mutation.clientMutationId(),
                        decimal(payload, "unitPrice"),
                        date(payload, "validFrom", true),
                        date(payload, "validTo", false),
                        text(payload, "source", true)
                )
        );
    }

    private ServicePriceVersion cancel(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context,
            JsonNode payload,
            String worksiteId,
            String entityId
    ) {
        requireNoRelated(mutation.relatedEntities());
        return service.cancelPrice(
                worksiteId,
                context.actorId(),
                entityId,
                new CancelServicePriceCommand(
                        mutation.clientMutationId(),
                        date(payload, "effectiveAt", true),
                        text(payload, "reason", true)
                )
        );
    }

    private void requireExactRelated(
            List<SyncPushRequest.RelatedEntity> related,
            String expectedType,
            String expectedId,
            String field
    ) {
        if (related == null || related.size() != 1) {
            throw badRequest(field + " não corresponde à entidade relacionada.");
        }
        SyncPushRequest.RelatedEntity item = related.getFirst();
        if (item == null
                || !expectedType.equals(item.tipo())
                || !expectedId.equals(item.id())) {
            throw badRequest(field + " não corresponde à entidade relacionada.");
        }
    }

    private void requireNoRelated(
            List<SyncPushRequest.RelatedEntity> related
    ) {
        if (related == null || !related.isEmpty()) {
            throw badRequest(
                    "O cancelamento não aceita entidades relacionadas do cliente."
            );
        }
    }

    private void requireOperation(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation == null
                || !OPERATIONS.contains(mutation.operacao())
                || !entityType().equals(mutation.entidadeTipo())) {
            throw badRequest("Operação de preço de serviço não suportada.");
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
            throw badRequest("payload do preço de serviço é obrigatório.");
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

    private BigDecimal decimal(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()
                || (!value.isNumber() && !value.isTextual())) {
            throw badRequest(field + " é obrigatório e deve ser decimal.");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw badRequest(field + " é inválido.");
        }
    }

    private LocalDate date(JsonNode payload, String field, boolean required) {
        String value = text(payload, field, required);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw badRequest(field + " é inválido.");
        }
    }

    private void requireAppliedId(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Preço persistiu identidade diferente do envelope."
            );
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

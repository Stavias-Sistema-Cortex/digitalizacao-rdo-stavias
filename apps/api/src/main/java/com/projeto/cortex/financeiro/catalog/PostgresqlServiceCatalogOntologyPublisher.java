package com.projeto.cortex.financeiro.catalog;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PostgresqlServiceCatalogOntologyPublisher
        implements ServiceCatalogOntologyPublisher {

    private static final String SOURCE = "CORTEX_FINANCEIRO";
    private static final String EVENT_NAMESPACE = "cortex:service-catalog:v1";

    private final CortexOperationalMemoryService memory;

    public PostgresqlServiceCatalogOntologyPublisher(
            CortexOperationalMemoryService memory
    ) {
        this.memory = memory;
    }

    @Override
    public void serviceCreated(
            ServiceCatalogEntry service,
            String obraId,
            String actorId,
            String clientMutationId
    ) {
        memory.registrarObjeto(
                "SERVICE", service.id(), service.code(), service.name(),
                service.status(), SOURCE, "catalogo_servico", Map.of()
        );
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("serviceId", service.id());
        state.put("serviceCode", service.code());
        state.put("serviceName", service.name());
        state.put("worksiteId", obraId);
        state.put("status", service.status());
        publish(
                "SERVICE_CREATED", "SERVICE", service.id(), obraId, actorId,
                clientMutationId, List.of(), state
        );
    }

    @Override
    public void priceVersionPublished(
            ServicePriceVersion price,
            ServiceCatalogEntry service,
            String actorId,
            String clientMutationId
    ) {
        memory.registrarObjeto(
                "SERVICE", service.id(), service.code(), service.name(),
                service.status(), SOURCE, "catalogo_servico", Map.of()
        );
        memory.registrarObjeto(
                "SERVICE_PRICE_VERSION",
                price.id(),
                null,
                priceName(service, price),
                price.status(),
                SOURCE,
                "service_price_version",
                Map.of()
        );
        memory.registrarRelacaoAtiva(
                "SERVICE", service.id(),
                "SERVICE_PRICE_VERSION", price.id(),
                "PRICED_BY", SOURCE,
                "Preço versionado por obra, unidade e moeda."
        );
        memory.registrarRelacaoAtiva(
                "SERVICE_PRICE_VERSION", price.id(),
                "WORKSITE", price.obraId(),
                "BELONGS_TO_WORKSITE", SOURCE,
                "Preço autorizado exclusivamente no escopo da obra."
        );

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("priceVersionId", price.id());
        state.put("serviceId", service.id());
        state.put("serviceName", service.name());
        state.put("worksiteId", price.obraId());
        state.put("unit", price.unit());
        state.put("currency", price.currency());
        state.put("unitPrice", price.unitPrice().toPlainString());
        if (price.contractedQuantity() != null) {
            state.put(
                    "contractedQuantity",
                    price.contractedQuantity().toPlainString()
            );
        }
        state.put("version", price.version());
        state.put("validFrom", price.validFrom().toString());
        if (price.validTo() != null) {
            state.put("validTo", price.validTo().toString());
        }
        if (price.supersedesId() != null) {
            state.put("supersedesId", price.supersedesId());
        }
        state.put("status", price.status());

        publish(
                "SERVICE_PRICE_VERSION_PUBLISHED",
                "SERVICE_PRICE_VERSION",
                price.id(),
                price.obraId(),
                actorId,
                clientMutationId,
                List.of(
                        Map.of("tipo", "SERVICE", "id", service.id()),
                        Map.of("tipo", "WORKSITE", "id", price.obraId())
                ),
                state
        );
    }

    @Override
    public void priceVersionSuperseded(
            ServicePriceVersion predecessor,
            ServicePriceVersion replacement,
            ServiceCatalogEntry service,
            String actorId,
            String clientMutationId
    ) {
        memory.registrarObjeto(
                "SERVICE", service.id(), service.code(), service.name(),
                service.status(), SOURCE, "catalogo_servico", Map.of()
        );
        memory.registrarObjeto(
                "SERVICE_PRICE_VERSION",
                predecessor.id(),
                null,
                priceName(service, predecessor),
                "SUPERSEDED",
                SOURCE,
                "service_price_version",
                Map.of()
        );
        memory.registrarObjeto(
                "SERVICE_PRICE_VERSION",
                replacement.id(),
                null,
                priceName(service, replacement),
                "ACTIVE",
                SOURCE,
                "service_price_version",
                Map.of()
        );
        memory.registrarRelacaoAtiva(
                "SERVICE", service.id(),
                "SERVICE_PRICE_VERSION", predecessor.id(),
                "PRICED_BY", SOURCE,
                "Preço versionado por obra, unidade e moeda."
        );
        memory.registrarRelacaoAtiva(
                "SERVICE", service.id(),
                "SERVICE_PRICE_VERSION", replacement.id(),
                "PRICED_BY", SOURCE,
                "Preço versionado por obra, unidade e moeda."
        );
        memory.registrarRelacaoAtiva(
                "SERVICE_PRICE_VERSION", replacement.id(),
                "SERVICE_PRICE_VERSION", predecessor.id(),
                "SUPERSEDES", SOURCE,
                "Nova versão substitui temporalmente a versão anterior."
        );
        memory.registrarRelacaoAtiva(
                "SERVICE_PRICE_VERSION", replacement.id(),
                "WORKSITE", replacement.obraId(),
                "BELONGS_TO_WORKSITE", SOURCE,
                "Preço autorizado exclusivamente no escopo da obra."
        );

        Map<String, Object> predecessorState = new LinkedHashMap<>();
        predecessorState.put("priceVersionId", predecessor.id());
        predecessorState.put("serviceId", service.id());
        predecessorState.put("serviceName", service.name());
        predecessorState.put("worksiteId", predecessor.obraId());
        predecessorState.put("status", "SUPERSEDED");
        predecessorState.put("supersededById", replacement.id());
        publish(
                "SERVICE_PRICE_VERSION_SUPERSEDED",
                "SERVICE_PRICE_VERSION",
                predecessor.id(),
                predecessor.obraId(),
                actorId,
                clientMutationId,
                List.of(
                        Map.of("tipo", "SERVICE", "id", service.id()),
                        Map.of("tipo", "WORKSITE", "id", predecessor.obraId())
                ),
                predecessorState
        );

        Map<String, Object> replacementState = new LinkedHashMap<>();
        replacementState.put("priceVersionId", replacement.id());
        replacementState.put("serviceId", service.id());
        replacementState.put("serviceName", service.name());
        replacementState.put("worksiteId", replacement.obraId());
        replacementState.put("unit", replacement.unit());
        replacementState.put("currency", replacement.currency());
        replacementState.put("unitPrice", replacement.unitPrice().toPlainString());
        if (replacement.contractedQuantity() != null) {
            replacementState.put(
                    "contractedQuantity",
                    replacement.contractedQuantity().toPlainString()
            );
        }
        replacementState.put("version", replacement.version());
        replacementState.put("validFrom", replacement.validFrom().toString());
        if (replacement.validTo() != null) {
            replacementState.put("validTo", replacement.validTo().toString());
        }
        replacementState.put("supersedesId", predecessor.id());
        replacementState.put("status", "ACTIVE");
        publish(
                "SERVICE_PRICE_VERSION_PUBLISHED",
                "SERVICE_PRICE_VERSION",
                replacement.id(),
                replacement.obraId(),
                actorId,
                clientMutationId,
                List.of(
                        Map.of("tipo", "SERVICE", "id", service.id()),
                        Map.of("tipo", "WORKSITE", "id", replacement.obraId()),
                        Map.of(
                                "tipo", "SERVICE_PRICE_VERSION",
                                "id", predecessor.id()
                        )
                ),
                replacementState
        );
    }

    @Override
    public void priceVersionCancelled(
            ServicePriceVersion price,
            ServiceCatalogEntry service,
            String actorId,
            String clientMutationId
    ) {
        memory.registrarObjeto(
                "SERVICE", service.id(), service.code(), service.name(),
                service.status(), SOURCE, "catalogo_servico", Map.of()
        );
        memory.registrarObjeto(
                "SERVICE_PRICE_VERSION",
                price.id(),
                null,
                priceName(service, price),
                "CANCELLED",
                SOURCE,
                "service_price_version",
                Map.of()
        );
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("priceVersionId", price.id());
        state.put("serviceId", service.id());
        state.put("serviceName", service.name());
        state.put("worksiteId", price.obraId());
        state.put("status", "CANCELLED");
        if (price.effectiveValidTo() != null) {
            state.put("effectiveValidTo", price.effectiveValidTo().toString());
        }
        publish(
                "SERVICE_PRICE_VERSION_CANCELLED",
                "SERVICE_PRICE_VERSION",
                price.id(),
                price.obraId(),
                actorId,
                clientMutationId,
                List.of(
                        Map.of("tipo", "SERVICE", "id", service.id()),
                        Map.of("tipo", "WORKSITE", "id", price.obraId())
                ),
                state
        );
    }

    private void publish(
            String eventType,
            String entityType,
            String entityId,
            String obraId,
            String actorId,
            String clientMutationId,
            List<Map<String, Object>> related,
            Map<String, Object> state
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        memory.registrarEventoAuditado(
                eventId(eventType, actorId, clientMutationId),
                entityType,
                entityId,
                eventType,
                SOURCE,
                obraId,
                null,
                actorId,
                new ArrayList<>(related),
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                state,
                actorId,
                null,
                clientMutationId,
                null,
                Map.of(),
                state,
                "SUCESSO",
                null
        );
    }

    static String eventId(
            String eventType,
            String actorId,
            String clientMutationId
    ) {
        String name = EVENT_NAMESPACE + "\0" + eventType + "\0"
                + actorId + "\0" + clientMutationId;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String priceName(
            ServiceCatalogEntry service,
            ServicePriceVersion price
    ) {
        return service.code() + " · " + price.unit() + " · "
                + price.currency() + " · v" + price.version();
    }
}

package com.projeto.cortex.ontology.graph;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Pure, deterministic projection from one committed operational event. */
@Component
public class OperationalGraphProjector {

    private static final String ID_NAMESPACE = "cortex:operational-graph:v1";
    private static final String CANONICAL_EVENT_SOURCE = "CANONICAL_OPERATIONAL_EVENT";
    static final String PROVIDED_CANONICAL_NAME = "_projectionProvidedCanonicalName";
    static final String PROVIDED_DESCRIPTION = "_projectionProvidedDescription";
    static final String PROVIDED_STATUS = "_projectionProvidedStatus";

    public GraphProjectionBatch project(CommittedOperationalEvent event) {
        Objects.requireNonNull(event, "Committed operational event is required.");

        EntityDescriptor principal = descriptor(event.principalEntity());
        List<EntityDescriptor> related = event.relatedEntities().stream()
                .map(this::descriptor)
                .sorted(Comparator.comparing(EntityDescriptor::type)
                        .thenComparing(EntityDescriptor::externalId))
                .toList();

        Map<String, GraphEntity> entitiesById = new LinkedHashMap<>();
        GraphEntity principalEntity = entity(principal, event, true);
        entitiesById.put(principalEntity.id(), principalEntity);
        for (EntityDescriptor descriptor : related) {
            GraphEntity relatedEntity = entity(descriptor, event, false);
            entitiesById.putIfAbsent(relatedEntity.id(), relatedEntity);
        }

        List<GraphRelation> relations = relations(principal, related, event.occurredAt());
        GraphEvent graphEvent = graphEvent(event, principal);
        List<GraphState> states = statusState(event, principal, graphEvent);
        List<GraphEvidence> evidences = revenueEvidence(event, principal);

        return new GraphProjectionBatch(
                event.commitSequence(),
                event.commitId(),
                entitiesById.values().stream()
                        .sorted(Comparator.comparing(GraphEntity::id))
                        .toList(),
                relations.stream()
                        .sorted(Comparator.comparing(GraphRelation::id))
                        .toList(),
                List.of(graphEvent),
                states,
                evidences
        );
    }

    private GraphEntity entity(
            EntityDescriptor descriptor,
            CommittedOperationalEvent event,
            boolean principal
    ) {
        String providedCanonicalName = principal
                ? firstText(event.payload(), nameKeys(descriptor.type()))
                : null;
        String canonicalName = Objects.requireNonNullElse(
                providedCanonicalName,
                descriptor.externalId()
        );
        String description = principal ? text(event.payload().get("description")) : null;
        String status = principal ? text(event.payload().get("status")) : null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authoritativeExternalId", descriptor.externalId());
        copyIfPresent(event.payload(), metadata, "worksiteId", "obraId");
        if (providedCanonicalName != null) {
            metadata.put(PROVIDED_CANONICAL_NAME, true);
        }
        if (description != null) {
            metadata.put(PROVIDED_DESCRIPTION, true);
        }
        if (status != null) {
            metadata.put(PROVIDED_STATUS, true);
        }

        return new GraphEntity(
                descriptor.id(),
                descriptor.type(),
                descriptor.externalRefType(),
                descriptor.externalId(),
                canonicalName,
                description,
                status,
                metadata,
                event.occurredAt(),
                event.occurredAt()
        );
    }

    private List<GraphRelation> relations(
            EntityDescriptor principal,
            List<EntityDescriptor> related,
            Instant occurredAt
    ) {
        List<GraphRelation> relations = new ArrayList<>();
        for (EntityDescriptor target : related) {
            String relationType = relationType(principal.type(), target.type());
            EntityDescriptor source = principal;
            EntityDescriptor relationTarget = target;

            if ("COLLABORATOR".equals(target.type()) && "RDO".equals(principal.type())) {
                relationType = "PARTICIPATES_IN";
                source = target;
                relationTarget = principal;
            } else if ("ASSET".equals(target.type()) && "RDO".equals(principal.type())) {
                relationType = "USED_IN";
                source = target;
                relationTarget = principal;
            }

            if (relationType != null) {
                relations.add(relation(source, relationType, relationTarget, occurredAt));
            }
        }
        return relations;
    }

    private static String relationType(String sourceType, String targetType) {
        if ("WORKSITE".equals(targetType)) {
            return "BELONGS_TO_WORKSITE";
        }
        if ("RDO".equals(targetType)) {
            if ("RDO".equals(sourceType)) {
                return "DERIVED_FROM";
            }
            if ("RDO_EXECUTION".equals(sourceType)) {
                return "RECORDED_IN";
            }
            if ("COLLABORATOR".equals(sourceType)) {
                return "PARTICIPATES_IN";
            }
            if ("ASSET".equals(sourceType)) {
                return "USED_IN";
            }
        }
        if ("RDO_EXECUTION".equals(sourceType) && "SERVICE".equals(targetType)) {
            return "EXECUTES_SERVICE";
        }
        if ("RDO_EXECUTION".equals(sourceType)
                && "SERVICE_PRICE_VERSION".equals(targetType)) {
            return "PRICED_BY";
        }
        if ("SERVICE_PRICE_VERSION".equals(sourceType) && "SERVICE".equals(targetType)) {
            return "PRICES";
        }
        return null;
    }

    private static GraphRelation relation(
            EntityDescriptor source,
            String type,
            EntityDescriptor target,
            Instant occurredAt
    ) {
        String externalKey = source.id() + ":" + type + ":" + target.id();
        return new GraphRelation(
                deterministicId("relation", externalKey),
                source.id(),
                type,
                target.id(),
                BigDecimal.ONE,
                Map.of(),
                occurredAt
        );
    }

    private static GraphEvent graphEvent(
            CommittedOperationalEvent event,
            EntityDescriptor principal
    ) {
        return new GraphEvent(
                deterministicId("event", event.commitId() + ":" + principal.id()),
                event.type(),
                principal.id(),
                null,
                CANONICAL_EVENT_SOURCE,
                event.commitId(),
                Objects.requireNonNullElse(
                        text(event.payload().get("description")),
                        event.type()
                ),
                event.payload(),
                event.occurredAt(),
                event.occurredAt()
        );
    }

    private static List<GraphState> statusState(
            CommittedOperationalEvent event,
            EntityDescriptor principal,
            GraphEvent sourceEvent
    ) {
        String status = text(event.payload().get("status"));
        if (status == null) {
            return List.of();
        }
        return List.of(new GraphState(
                deterministicId("state", principal.id() + ":STATUS:" + event.commitId()),
                principal.id(),
                "STATUS",
                status,
                null,
                null,
                event.occurredAt(),
                null,
                sourceEvent.id(),
                Map.of("commitId", event.commitId())
        ));
    }

    private static List<GraphEvidence> revenueEvidence(
            CommittedOperationalEvent event,
            EntityDescriptor principal
    ) {
        if (!"RDO_SERVICE_EXECUTED".equals(event.type())) {
            return List.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyIfPresent(event.payload(), metadata,
                "acceptedQuantity", "quantity", "unitPrice", "revenue", "unit",
                "priceVersionId", "worksiteId", "obraId", "rdoId", "serviceId");
        return List.of(new GraphEvidence(
                deterministicId("evidence", "REVENUE:" + event.commitId() + ":" + principal.id()),
                principal.id(),
                "REVENUE_EVIDENCE",
                CANONICAL_EVENT_SOURCE,
                event.commitId(),
                "Revenue evidence from " + event.type() + ".",
                null,
                metadata,
                event.occurredAt()
        ));
    }

    private EntityDescriptor descriptor(CommittedOperationalEvent.EntityRef reference) {
        String type = normalizeType(reference.type());
        String externalRefType = switch (type) {
            case "WORKSITE" -> "obra";
            case "RDO" -> "rdo";
            case "COLLABORATOR" -> "colaborador";
            case "ASSET" -> "asset";
            case "SERVICE" -> "item_contratual";
            case "SERVICE_PRICE_VERSION" -> "service_price_version";
            case "RDO_EXECUTION" -> "execucao_servico_rdo";
            default -> type.toLowerCase(Locale.ROOT);
        };
        return new EntityDescriptor(
                deterministicId("entity:" + type, reference.id()),
                type,
                externalRefType,
                reference.id()
        );
    }

    private static String normalizeType(String raw) {
        String type = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (type) {
            case "OBRA" -> "WORKSITE";
            case "COLABORADOR" -> "COLLABORATOR";
            case "EQUIPAMENTO", "EQUIPMENT" -> "ASSET";
            case "ITEM_CONTRATUAL" -> "SERVICE";
            case "PRECO_SERVICO", "SERVICE_PRICE" -> "SERVICE_PRICE_VERSION";
            case "EXECUTION", "EXECUCAO_SERVICO_RDO" -> "RDO_EXECUTION";
            default -> type;
        };
    }

    private static String[] nameKeys(String type) {
        return switch (type) {
            case "WORKSITE" -> new String[]{"worksiteName", "obraName", "name"};
            case "RDO" -> new String[]{"rdoNumber", "number", "name"};
            case "COLLABORATOR" -> new String[]{"collaboratorName", "name"};
            case "ASSET" -> new String[]{"assetName", "name"};
            case "SERVICE" -> new String[]{"serviceName", "name"};
            default -> new String[]{"name"};
        };
    }

    private static String firstText(Map<String, Object> values, String[] keys) {
        for (String key : keys) {
            String value = text(values.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static void copyIfPresent(
            Map<String, Object> source,
            Map<String, Object> destination,
            String... keys
    ) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                destination.put(key, source.get(key));
            }
        }
    }

    private static String deterministicId(String recordKind, String authoritativeExternalId) {
        byte[] name = (ID_NAMESPACE + "\u0000" + recordKind + "\u0000"
                + authoritativeExternalId).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(name).toString();
    }

    private record EntityDescriptor(
            String id,
            String type,
            String externalRefType,
            String externalId
    ) {
    }
}

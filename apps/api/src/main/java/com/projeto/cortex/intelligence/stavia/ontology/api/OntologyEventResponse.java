package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEvent;
import java.time.Instant;
import java.util.Map;

public record OntologyEventResponse(
        String id,
        String eventType,
        String entityId,
        String relatedEntityId,
        String sourceType,
        String sourceId,
        String description,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant createdAt
) {

    public static OntologyEventResponse from(OntologyEvent event) {
        return new OntologyEventResponse(
                event.id(),
                event.eventType(),
                event.entityId(),
                event.relatedEntityId(),
                event.sourceType(),
                event.sourceId(),
                event.description(),
                event.payload(),
                event.occurredAt(),
                event.createdAt()
        );
    }
}

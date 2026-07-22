package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.time.Instant;
import java.util.Map;

public record OntologyEvent(
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

    public OntologyEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("O ID do evento ontológico deve ser informado.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("O tipo do evento ontológico deve ser informado.");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("A entidade do evento ontológico deve ser informada.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A descrição do evento ontológico deve ser informada.");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}

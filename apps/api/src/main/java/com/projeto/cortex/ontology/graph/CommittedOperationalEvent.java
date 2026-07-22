package com.projeto.cortex.ontology.graph;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CommittedOperationalEvent(
        long commitSequence,
        String commitId,
        String type,
        EntityRef principalEntity,
        List<EntityRef> relatedEntities,
        Instant occurredAt,
        Map<String, Object> payload
) {

    public CommittedOperationalEvent {
        if (commitSequence < 0) {
            throw new IllegalArgumentException("Operational event commit sequence cannot be negative.");
        }
        requireText(commitId, "Operational event commit id is required.");
        requireText(type, "Operational event type is required.");
        if (principalEntity == null) {
            throw new IllegalArgumentException("Operational event principal entity is required.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Operational event occurrence time is required.");
        }
        relatedEntities = relatedEntities == null ? List.of() : List.copyOf(relatedEntities);
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public record EntityRef(String type, String id) {

        public EntityRef {
            requireText(type, "Operational event entity type is required.");
            requireText(id, "Operational event entity id is required.");
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

package com.projeto.cortex.ontology.graph;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphEvent(
        String id,
        String type,
        String entityId,
        String relatedEntityId,
        String sourceType,
        String sourceId,
        String description,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant createdAt
) {

    public GraphEvent {
        requireText(id, "Graph event id is required.");
        requireText(type, "Graph event type is required.");
        requireText(entityId, "Graph event entity id is required.");
        requireText(description, "Graph event description is required.");
        requireInstant(occurredAt, "Graph event occurrence time is required.");
        requireInstant(createdAt, "Graph event creation time is required.");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireInstant(Instant value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }
}

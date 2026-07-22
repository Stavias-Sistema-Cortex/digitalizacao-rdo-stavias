package com.projeto.cortex.ontology.graph;

import java.time.Instant;
import java.util.Map;

public record GraphEntity(
        String id,
        String type,
        String externalRefType,
        String externalRefId,
        String canonicalName,
        String description,
        String status,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public GraphEntity {
        requireText(id, "Graph entity id is required.");
        requireText(type, "Graph entity type is required.");
        requireText(canonicalName, "Graph entity canonical name is required.");
        requireInstant(createdAt, "Graph entity creation time is required.");
        requireInstant(updatedAt, "Graph entity update time is required.");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
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

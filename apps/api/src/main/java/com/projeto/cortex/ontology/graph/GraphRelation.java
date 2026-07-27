package com.projeto.cortex.ontology.graph;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record GraphRelation(
        String id,
        String sourceEntityId,
        String type,
        String targetEntityId,
        BigDecimal confidence,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public GraphRelation {
        requireText(id, "Graph relation id is required.");
        requireText(sourceEntityId, "Graph relation source entity id is required.");
        requireText(type, "Graph relation type is required.");
        requireText(targetEntityId, "Graph relation target entity id is required.");
        if (createdAt == null) {
            throw new IllegalArgumentException("Graph relation creation time is required.");
        }
        confidence = confidence == null ? BigDecimal.ONE : confidence;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

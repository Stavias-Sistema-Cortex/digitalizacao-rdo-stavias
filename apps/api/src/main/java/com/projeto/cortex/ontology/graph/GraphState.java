package com.projeto.cortex.ontology.graph;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record GraphState(
        String id,
        String entityId,
        String type,
        String value,
        BigDecimal numericValue,
        String unit,
        Instant validFrom,
        Instant validTo,
        String sourceEventId,
        Map<String, Object> metadata
) {

    public GraphState {
        requireText(id, "Graph state id is required.");
        requireText(entityId, "Graph state entity id is required.");
        requireText(type, "Graph state type is required.");
        if (validFrom == null) {
            throw new IllegalArgumentException("Graph state valid-from time is required.");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("Graph state valid-to time cannot precede valid-from time.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

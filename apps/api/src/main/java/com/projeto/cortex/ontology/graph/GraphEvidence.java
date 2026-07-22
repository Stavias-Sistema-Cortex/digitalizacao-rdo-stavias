package com.projeto.cortex.ontology.graph;

import java.time.Instant;
import java.util.Map;

public record GraphEvidence(
        String id,
        String entityId,
        String type,
        String sourceType,
        String sourceId,
        String description,
        String fileRef,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public GraphEvidence {
        requireText(id, "Graph evidence id is required.");
        requireText(entityId, "Graph evidence entity id is required.");
        requireText(type, "Graph evidence type is required.");
        requireText(sourceType, "Graph evidence source type is required.");
        requireText(sourceId, "Graph evidence source id is required.");
        requireText(description, "Graph evidence description is required.");
        if (createdAt == null) {
            throw new IllegalArgumentException("Graph evidence creation time is required.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

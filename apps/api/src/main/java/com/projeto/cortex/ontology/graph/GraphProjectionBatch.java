package com.projeto.cortex.ontology.graph;

import java.util.List;

public record GraphProjectionBatch(
        long commitSequence,
        String commitId,
        List<GraphEntity> entities,
        List<GraphRelation> relations,
        List<GraphEvent> events,
        List<GraphState> states,
        List<GraphEvidence> evidences
) {

    public GraphProjectionBatch {
        if (commitSequence < 0) {
            throw new IllegalArgumentException("Graph projection commit sequence cannot be negative.");
        }
        requireText(commitId, "Graph projection commit id is required.");
        entities = copyOf(entities);
        relations = copyOf(relations);
        events = copyOf(events);
        states = copyOf(states);
        evidences = copyOf(evidences);
    }

    private static <T> List<T> copyOf(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

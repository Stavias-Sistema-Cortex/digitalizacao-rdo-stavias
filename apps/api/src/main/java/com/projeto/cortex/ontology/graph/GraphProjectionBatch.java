package com.projeto.cortex.ontology.graph;

import java.util.List;

public record GraphProjectionBatch(
        long commitSequence,
        String commitId,
        List<GraphEntity> entities,
        List<GraphRelation> relations,
        List<GraphEvent> events,
        List<GraphState> states,
        List<GraphEvidence> evidences,
        String sourceWorksiteId
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
        sourceWorksiteId = normalized(sourceWorksiteId);
    }

    public GraphProjectionBatch(
            long commitSequence,
            String commitId,
            List<GraphEntity> entities,
            List<GraphRelation> relations,
            List<GraphEvent> events,
            List<GraphState> states,
            List<GraphEvidence> evidences
    ) {
        this(
                commitSequence,
                commitId,
                entities,
                relations,
                events,
                states,
                evidences,
                null
        );
    }

    private static <T> List<T> copyOf(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

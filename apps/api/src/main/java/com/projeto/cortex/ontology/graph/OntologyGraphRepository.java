package com.projeto.cortex.ontology.graph;

import java.util.OptionalLong;

public interface OntologyGraphRepository {

    void upsert(GraphProjectionBatch batch);

    OptionalLong currentCheckpoint();

    void markProjectionFailure(long commitSequence, String safeCode);
}

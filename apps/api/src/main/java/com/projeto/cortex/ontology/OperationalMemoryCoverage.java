package com.projeto.cortex.ontology;

public record OperationalMemoryCoverage(
        String mode,
        boolean complete,
        long authorizedEventCount,
        Long oldestCommitSequence,
        long newestCommitSequence,
        OperationalMemoryGraphCoverage graph
) {

    public OperationalMemoryCoverage {
        if (graph == null) {
            throw new IllegalArgumentException("Memory graph coverage is required.");
        }
    }
}

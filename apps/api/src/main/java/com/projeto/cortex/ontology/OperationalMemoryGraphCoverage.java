package com.projeto.cortex.ontology;

public record OperationalMemoryGraphCoverage(
        long checkpointCommitSequence,
        long targetCommitSequence,
        long lagEventCount,
        boolean fresh,
        String lastSafeError
) {

    public OperationalMemoryGraphCoverage {
        if (checkpointCommitSequence < 0
                || targetCommitSequence < 0
                || lagEventCount < 0) {
            throw new IllegalArgumentException("Memory graph coverage cannot be negative.");
        }
        if (fresh != (lagEventCount == 0)) {
            throw new IllegalArgumentException("Memory graph freshness and lag disagree.");
        }
    }
}

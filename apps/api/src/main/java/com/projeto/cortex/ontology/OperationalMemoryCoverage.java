package com.projeto.cortex.ontology;

public record OperationalMemoryCoverage(
        String mode,
        boolean complete,
        long authorizedEventCount,
        Long oldestCommitSequence,
        long newestCommitSequence
) {
}

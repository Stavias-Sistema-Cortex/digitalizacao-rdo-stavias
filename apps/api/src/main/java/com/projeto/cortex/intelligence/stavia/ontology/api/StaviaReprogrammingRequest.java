package com.projeto.cortex.intelligence.stavia.ontology.api;

public record StaviaReprogrammingRequest(
        String userId,
        String obraId,
        String period,
        Integer targetRecoveryDays
) {
}

package com.projeto.cortex.intelligence.stavia.ontology.api;

public record StaviaOperationalQueryRequest(
        String userId,
        String query,
        StaviaOperationalScope scope
) {
}

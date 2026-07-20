package com.projeto.cortex.intelligence.stavia.ontology.api;

import java.util.List;
import java.util.Map;

public record StaviaOperationalQueryResponse(
        String answer,
        String intent,
        List<Map<String, Object>> entitiesUsed,
        List<Map<String, Object>> eventsUsed,
        List<Map<String, Object>> relationsUsed,
        double confidence,
        String queryId
) {

    public StaviaOperationalQueryResponse {
        entitiesUsed = entitiesUsed == null ? List.of() : List.copyOf(entitiesUsed);
        eventsUsed = eventsUsed == null ? List.of() : List.copyOf(eventsUsed);
        relationsUsed = relationsUsed == null ? List.of() : List.copyOf(relationsUsed);
    }
}

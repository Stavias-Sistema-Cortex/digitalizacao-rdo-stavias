package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.util.List;

public record CriticalPathImpact(
        OntologyEntity activity,
        List<OntologyEntity> dependentActivities,
        List<String> riskMessages,
        double confidence
) {

    public CriticalPathImpact {
        dependentActivities = dependentActivities == null ? List.of() : List.copyOf(dependentActivities);
        riskMessages = riskMessages == null ? List.of() : List.copyOf(riskMessages);
    }
}

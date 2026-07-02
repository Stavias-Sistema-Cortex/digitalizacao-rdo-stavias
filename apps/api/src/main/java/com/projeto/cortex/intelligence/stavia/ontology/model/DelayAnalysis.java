package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.math.BigDecimal;
import java.util.List;

public record DelayAnalysis(
        OntologyEntity activity,
        BigDecimal delayDays,
        BigDecimal progressPercent,
        String status,
        List<String> confirmedCauses,
        List<String> probableCauses,
        List<String> impacts,
        List<OperationalEvidence> evidences,
        List<OntologyRelation> blockingRelations,
        double confidence
) {

    public DelayAnalysis {
        confirmedCauses = confirmedCauses == null ? List.of() : List.copyOf(confirmedCauses);
        probableCauses = probableCauses == null ? List.of() : List.copyOf(probableCauses);
        impacts = impacts == null ? List.of() : List.copyOf(impacts);
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        blockingRelations = blockingRelations == null ? List.of() : List.copyOf(blockingRelations);
    }
}

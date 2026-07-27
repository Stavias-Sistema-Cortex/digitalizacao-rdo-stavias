package com.projeto.cortex.intelligence.stavia.policy;

import java.util.List;

public record StaviaQualityAssessment(
        StaviaEvidenceQuality quality,
        boolean stale,
        List<String> warnings
) {

    public StaviaQualityAssessment {
        if (quality == null) {
            throw new IllegalArgumentException(
                    "A qualidade das evidências deve ser informada."
            );
        }

        warnings = warnings == null
                ? List.of()
                : List.copyOf(warnings);
    }
}

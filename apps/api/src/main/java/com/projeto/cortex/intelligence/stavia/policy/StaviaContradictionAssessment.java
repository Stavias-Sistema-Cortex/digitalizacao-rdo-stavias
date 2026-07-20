package com.projeto.cortex.intelligence.stavia.policy;

import java.util.List;

public record StaviaContradictionAssessment(
        boolean contradictory,
        boolean critical,
        List<String> conflictingFields,
        List<String> warnings
) {

    public StaviaContradictionAssessment {
        conflictingFields = conflictingFields == null
                ? List.of()
                : List.copyOf(conflictingFields);

        warnings = warnings == null
                ? List.of()
                : List.copyOf(warnings);
    }

    public static StaviaContradictionAssessment none() {
        return new StaviaContradictionAssessment(
                false,
                false,
                List.of(),
                List.of()
        );
    }
}

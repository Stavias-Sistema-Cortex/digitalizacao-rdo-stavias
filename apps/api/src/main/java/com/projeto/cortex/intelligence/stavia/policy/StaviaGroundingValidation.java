package com.projeto.cortex.intelligence.stavia.policy;

import java.util.List;

public record StaviaGroundingValidation(
        boolean valid,
        List<String> violations
) {

    public StaviaGroundingValidation {
        violations = violations == null
                ? List.of()
                : List.copyOf(violations);
    }

    public static StaviaGroundingValidation success() {
        return new StaviaGroundingValidation(
                true,
                List.of()
        );
    }

    public static StaviaGroundingValidation failure(
            List<String> violations
    ) {
        return new StaviaGroundingValidation(
                false,
                violations
        );
    }
}

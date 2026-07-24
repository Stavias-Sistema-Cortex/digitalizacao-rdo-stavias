package com.projeto.cortex.intelligence.stavia.planning;

import java.time.LocalDate;

public record TemporalFilter(
        LocalDate startDate,
        LocalDate endDate,
        String relativeDate,
        String shift,
        String latestCriterion
) {

    public TemporalFilter {
        relativeDate = normalize(relativeDate);
        shift = normalize(shift);
        latestCriterion = normalize(latestCriterion);
    }

    public static TemporalFilter none() {
        return new TemporalFilter(
                null,
                null,
                null,
                null,
                null
        );
    }

    public static TemporalFilter latest(String criterion) {
        return new TemporalFilter(
                null,
                null,
                "MAIS_RECENTE",
                null,
                criterion
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}

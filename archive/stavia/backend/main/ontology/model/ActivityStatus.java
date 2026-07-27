package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ActivityStatus(
        OntologyEntity activity,
        String status,
        BigDecimal progressPercent,
        BigDecimal delayDays,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        boolean blocked
) {
}

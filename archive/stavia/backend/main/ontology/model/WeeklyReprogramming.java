package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.time.LocalDate;
import java.util.List;

public record WeeklyReprogramming(
        LocalDate periodStart,
        LocalDate periodEnd,
        String objective,
        List<String> priorities,
        List<String> recommendedActions,
        List<String> dependencies,
        List<String> risks,
        List<String> milestones,
        List<String> idsUsed,
        double confidence
) {

    public WeeklyReprogramming {
        priorities = priorities == null ? List.of() : List.copyOf(priorities);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        risks = risks == null ? List.of() : List.copyOf(risks);
        milestones = milestones == null ? List.of() : List.copyOf(milestones);
        idsUsed = idsUsed == null ? List.of() : List.copyOf(idsUsed);
    }
}

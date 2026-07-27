package com.projeto.cortex.ontology;

import java.util.Set;
import java.util.TreeSet;

public record OperationalMemoryScope(
        String userId,
        boolean global,
        Set<String> allowedWorksiteIds,
        Set<String> financialWorksiteIds,
        Set<String> financialUnitIds
) {
    public OperationalMemoryScope {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Operational Memory user is required.");
        }
        userId = userId.trim();
        TreeSet<String> normalized = new TreeSet<>();
        if (allowedWorksiteIds != null) {
            allowedWorksiteIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(normalized::add);
        }
        allowedWorksiteIds = Set.copyOf(normalized);
        financialWorksiteIds = normalized(financialWorksiteIds);
        financialUnitIds = normalized(financialUnitIds);
        if (global && (!allowedWorksiteIds.isEmpty()
                || !financialWorksiteIds.isEmpty()
                || !financialUnitIds.isEmpty())) {
            throw new IllegalArgumentException(
                    "A global Operational Memory scope cannot contain worksites."
            );
        }
    }

    public static OperationalMemoryScope alfa(String userId) {
        return new OperationalMemoryScope(userId, true, Set.of(), Set.of(), Set.of());
    }

    public static OperationalMemoryScope beta(String userId, Set<String> allowedWorksiteIds) {
        return beta(userId, allowedWorksiteIds, Set.of(), Set.of());
    }

    public static OperationalMemoryScope beta(
            String userId,
            Set<String> allowedWorksiteIds,
            Set<String> financialWorksiteIds,
            Set<String> financialUnitIds
    ) {
        return new OperationalMemoryScope(
                userId,
                false,
                allowedWorksiteIds,
                financialWorksiteIds,
                financialUnitIds
        );
    }

    public String label() {
        return global ? "GLOBAL" : "AUTHORIZED_WORKSITES";
    }

    private static Set<String> normalized(Set<String> values) {
        TreeSet<String> normalized = new TreeSet<>();
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(normalized::add);
        }
        return Set.copyOf(normalized);
    }
}

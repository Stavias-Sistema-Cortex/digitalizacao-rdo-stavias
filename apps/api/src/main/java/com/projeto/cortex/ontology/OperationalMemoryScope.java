package com.projeto.cortex.ontology;

import java.util.Set;
import java.util.TreeSet;

public record OperationalMemoryScope(
        String userId,
        boolean global,
        Set<String> allowedWorksiteIds
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
        if (global && !allowedWorksiteIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "A global Operational Memory scope cannot contain worksites."
            );
        }
    }

    public static OperationalMemoryScope alfa(String userId) {
        return new OperationalMemoryScope(userId, true, Set.of());
    }

    public static OperationalMemoryScope beta(String userId, Set<String> allowedWorksiteIds) {
        return new OperationalMemoryScope(userId, false, allowedWorksiteIds);
    }

    public String label() {
        return global ? "GLOBAL" : "AUTHORIZED_WORKSITES";
    }
}

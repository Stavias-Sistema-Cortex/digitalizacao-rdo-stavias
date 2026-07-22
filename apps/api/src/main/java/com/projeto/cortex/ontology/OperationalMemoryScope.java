package com.projeto.cortex.ontology;

import java.util.Set;

public record OperationalMemoryScope(
        String userId,
        boolean global,
        Set<String> allowedWorksiteIds
) {
    public OperationalMemoryScope {
        userId = userId == null ? null : userId.trim();
        allowedWorksiteIds = allowedWorksiteIds == null
                ? Set.of()
                : Set.copyOf(allowedWorksiteIds);
    }

    public static OperationalMemoryScope alfa(String userId) {
        return new OperationalMemoryScope(userId, true, Set.of());
    }

    public static OperationalMemoryScope beta(
            String userId,
            Set<String> allowedWorksiteIds
    ) {
        return new OperationalMemoryScope(
                userId,
                false,
                allowedWorksiteIds
        );
    }

    public String label() {
        return global ? "GLOBAL" : "AUTHORIZED_WORKSITES";
    }
}

package com.projeto.cortex.intelligence.stavia.model;

import java.util.List;
import java.util.Set;

public record StaviaContext(
        Set<String> permissions,
        List<StaviaEvidence> evidences
) {

    public StaviaContext {
        permissions = permissions == null
                ? Set.of()
                : Set.copyOf(permissions);

        evidences = evidences == null
                ? List.of()
                : List.copyOf(evidences);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}

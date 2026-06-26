package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record StaviaEntityFilters(
        String collaboratorNameNormalized,
        Set<String> rolesNormalized
) {

    public StaviaEntityFilters {
        rolesNormalized = rolesNormalized == null ? Set.of() : Set.copyOf(rolesNormalized);
    }

    public static StaviaEntityFilters from(List<ResolvedEntity> entities) {
        String collaborator = null;
        Set<String> roles = new LinkedHashSet<>();

        if (entities != null) {
            for (ResolvedEntity entity : entities) {
                if (entity == null || entity.value() == null) {
                    continue;
                }
                String normalized = StaviaText.normalize(entity.value());
                if (normalized.isBlank()) {
                    continue;
                }
                if ("COLABORADOR".equalsIgnoreCase(entity.type()) && collaborator == null) {
                    collaborator = normalized;
                } else if ("ROLE".equalsIgnoreCase(entity.type())) {
                    roles.add(normalized);
                }
            }
        }

        return new StaviaEntityFilters(collaborator, roles);
    }

    public Optional<String> collaboratorName() {
        return Optional.ofNullable(collaboratorNameNormalized);
    }

    public Set<String> roles() {
        return rolesNormalized;
    }

    public boolean isEmpty() {
        return collaboratorNameNormalized == null && rolesNormalized.isEmpty();
    }

    public boolean matchesCollaborator(String candidate) {
        if (collaboratorNameNormalized == null) {
            return true;
        }
        return StaviaText.normalize(candidate).contains(collaboratorNameNormalized);
    }

    public boolean matchesRole(String candidate) {
        if (rolesNormalized.isEmpty()) {
            return true;
        }
        String normalized = StaviaText.normalize(candidate);
        return rolesNormalized.stream().anyMatch(normalized::contains);
    }
}

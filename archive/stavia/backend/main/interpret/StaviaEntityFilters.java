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

        String normalizedCandidate = StaviaText.normalize(candidate);

        if (normalizedCandidate.contains(collaboratorNameNormalized)) {
            return true;
        }

        String[] requestedTokens = collaboratorNameNormalized.split("\\s+");
        String[] candidateTokens = normalizedCandidate.split("\\s+");

        if (requestedTokens.length < 2 || candidateTokens.length == 0) {
            return false;
        }

        for (String requested : requestedTokens) {
            boolean matched = false;

            for (String available : candidateTokens) {
                if (requested.equals(available)
                        || (requested.length() >= 4
                                && levenshteinDistance(
                                        requested,
                                        available
                                ) <= 1)) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                return false;
            }
        }

        return true;
    }

    public boolean matchesRole(String candidate) {
        if (rolesNormalized.isEmpty()) {
            return true;
        }
        String normalized = StaviaText.normalize(candidate);
        return rolesNormalized.stream().anyMatch(normalized::contains);
    }

    private int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];

        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }

        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;

            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int cost = left.charAt(leftIndex - 1)
                        == right.charAt(rightIndex - 1)
                        ? 0
                        : 1;

                current[rightIndex] = Math.min(
                        Math.min(
                                current[rightIndex - 1] + 1,
                                previous[rightIndex] + 1
                        ),
                        previous[rightIndex - 1] + cost
                );
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[right.length()];
    }
}

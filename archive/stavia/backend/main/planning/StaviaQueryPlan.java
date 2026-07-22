package com.projeto.cortex.intelligence.stavia.planning;

import java.util.List;

public record StaviaQueryPlan(
        QueryDomain domain,
        QueryOperation operation,
        List<ResolvedEntity> entities,
        TemporalFilter temporalFilter,
        List<String> requestedAttributes,
        List<String> requestedRelationships,
        List<AggregationSpec> aggregations,
        List<String> requiredSources,
        boolean requiresLatestValue,
        boolean requiresHistory,
        boolean requiresComparison
) {

    public StaviaQueryPlan {
        domain = domain == null
                ? QueryDomain.DESCONHECIDO
                : domain;
        operation = operation == null
                ? QueryOperation.UNKNOWN
                : operation;
        entities = entities == null
                ? List.of()
                : List.copyOf(entities);
        temporalFilter = temporalFilter == null
                ? TemporalFilter.none()
                : temporalFilter;
        requestedAttributes = copyText(requestedAttributes);
        requestedRelationships = copyText(requestedRelationships);
        aggregations = aggregations == null
                ? List.of()
                : List.copyOf(aggregations);
        requiredSources = copyText(requiredSources);
    }

    public static StaviaQueryPlan empty() {
        return new StaviaQueryPlan(
                QueryDomain.DESCONHECIDO,
                QueryOperation.UNKNOWN,
                List.of(),
                TemporalFilter.none(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false
        );
    }

    public boolean planned() {
        return domain != QueryDomain.DESCONHECIDO
                && operation != QueryOperation.UNKNOWN;
    }

    private static List<String> copyText(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}

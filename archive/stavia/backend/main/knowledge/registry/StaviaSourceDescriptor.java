package com.projeto.cortex.intelligence.stavia.knowledge.registry;

import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;

import java.util.Set;

public record StaviaSourceDescriptor(
        String name,
        String version,
        Set<QueryDomain> domains,
        Set<String> objectTypes,
        Set<String> attributes,
        Set<String> relationships,
        Set<QueryOperation> operations,
        Set<String> permissions,
        String freshness,
        int priority,
        int resultLimit
) {

    public StaviaSourceDescriptor {
        name = normalize(name);
        version = normalize(version);
        domains = copy(domains);
        objectTypes = copyText(objectTypes);
        attributes = copyText(attributes);
        relationships = copyText(relationships);
        operations = copy(operations);
        permissions = copyText(permissions);
        freshness = normalize(freshness);
        priority = Math.max(0, priority);
        resultLimit = Math.max(1, resultLimit);
    }

    public static StaviaSourceDescriptor legacy(
            String name,
            String version
    ) {
        return new StaviaSourceDescriptor(
                name,
                version,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of("STAVIA_CONSULTAR"),
                "Sob demanda",
                100,
                50
        );
    }

    private static <T> Set<T> copy(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static Set<String> copyText(Set<String> values) {
        if (values == null) {
            return Set.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}

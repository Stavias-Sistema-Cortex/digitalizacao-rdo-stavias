package com.projeto.cortex.ontology;

import java.time.Instant;
import java.util.Locale;

public record OperationalMemoryFilter(
        String query,
        String entityType,
        String entityId,
        String worksiteId,
        String rdoId,
        String actorId,
        String eventType,
        String origin,
        String result,
        Instant from,
        Instant to
) {
    public OperationalMemoryFilter {
        query = trimToNull(query);
        entityType = code(entityType);
        entityId = trimToNull(entityId);
        worksiteId = trimToNull(worksiteId);
        rdoId = trimToNull(rdoId);
        actorId = trimToNull(actorId);
        eventType = code(eventType);
        origin = code(origin);
        result = code(result);
    }

    public static OperationalMemoryFilter empty() {
        return new OperationalMemoryFilter(
                null, null, null, null, null, null, null, null, null, null, null
        );
    }

    private static String code(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

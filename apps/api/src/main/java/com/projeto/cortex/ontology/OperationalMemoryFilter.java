package com.projeto.cortex.ontology;

import java.time.LocalDateTime;

public record OperationalMemoryFilter(
        String entityType,
        String entityId,
        String obraId,
        String rdoId,
        String actorId,
        String eventType,
        String origin,
        String result,
        LocalDateTime from,
        LocalDateTime to
) {
    public OperationalMemoryFilter {
        entityType = trimToNull(entityType);
        entityId = trimToNull(entityId);
        obraId = trimToNull(obraId);
        rdoId = trimToNull(rdoId);
        actorId = trimToNull(actorId);
        eventType = trimToNull(eventType);
        origin = trimToNull(origin);
        result = trimToNull(result);
    }

    public static OperationalMemoryFilter empty() {
        return new OperationalMemoryFilter(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

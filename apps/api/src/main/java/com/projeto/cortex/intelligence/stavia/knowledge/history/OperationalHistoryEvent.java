package com.projeto.cortex.intelligence.stavia.knowledge.history;

import java.time.Instant;
import java.util.Map;

public record OperationalHistoryEvent(
        String eventId,
        long commitSequence,
        String entityType,
        String entityId,
        String eventType,
        String source,
        Instant occurredAt,
        Map<String, Object> payload
) {

    public OperationalHistoryEvent {
        requireText(eventId, "O identificador do evento é obrigatório.");
        requireText(entityType, "O tipo da entidade é obrigatório.");
        requireText(entityId, "O identificador da entidade é obrigatório.");
        requireText(eventType, "O tipo do evento é obrigatório.");
        requireText(source, "A fonte do evento é obrigatória.");

        if (occurredAt == null) {
            throw new IllegalArgumentException(
                    "A data do evento é obrigatória."
            );
        }

        payload = payload == null
                ? Map.of()
                : Map.copyOf(payload);
    }

    private static void requireText(
            String value,
            String message
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

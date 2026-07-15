package com.projeto.cortex.equipes;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record EquipeHistoryResponse(
        long commitSeq,
        String eventId,
        String entityType,
        String entityId,
        String eventType,
        String source,
        String worksiteId,
        String collaboratorId,
        LocalDateTime occurredAt,
        LocalDateTime recordedAt,
        long entityVersion,
        JsonNode payload
) {
}

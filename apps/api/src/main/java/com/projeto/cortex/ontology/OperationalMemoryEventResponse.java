package com.projeto.cortex.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record OperationalMemoryEventResponse(
        String id,
        Long commitSeq,
        String type,
        String source,
        String principalEntityType,
        String principalEntityId,
        JsonNode relatedEntities,
        String obraId,
        String rdoId,
        String colaboradorId,
        LocalDateTime occurredAt,
        LocalDateTime syncedAt,
        String origin,
        String syncStatus,
        Integer schemaVersion,
        String actorId,
        String actorName,
        String deviceId,
        String correlationId,
        String causationId,
        JsonNode previousState,
        JsonNode newState,
        String result,
        String errorCategory,
        JsonNode payload
) {
}

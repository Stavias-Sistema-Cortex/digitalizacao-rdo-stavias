package com.projeto.cortex.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record OperationalTimelineEventResponse(
        String id,
        Long commitSeq,
        String type,
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
        JsonNode payload
) {
}

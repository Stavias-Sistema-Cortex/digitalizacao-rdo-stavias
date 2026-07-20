package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.time.Instant;
import java.util.Map;

public record OperationalDecision(
        String id,
        String entityId,
        String decisionType,
        String description,
        String decidedBy,
        Instant decidedAt,
        String expectedImpact,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public OperationalDecision {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("O ID da decisão operacional deve ser informado.");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("A entidade da decisão operacional deve ser informada.");
        }
        if (decisionType == null || decisionType.isBlank()) {
            throw new IllegalArgumentException("O tipo da decisão operacional deve ser informado.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A descrição da decisão operacional deve ser informada.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

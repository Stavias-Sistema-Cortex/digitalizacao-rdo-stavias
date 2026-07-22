package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.time.Instant;
import java.util.Map;

public record OperationalConstraint(
        String id,
        String entityId,
        String constraintType,
        String description,
        String severity,
        String status,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant resolvedAt
) {

    public OperationalConstraint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("O ID da restrição operacional deve ser informado.");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("A entidade da restrição operacional deve ser informada.");
        }
        if (constraintType == null || constraintType.isBlank()) {
            throw new IllegalArgumentException("O tipo da restrição operacional deve ser informado.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A descrição da restrição operacional deve ser informada.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

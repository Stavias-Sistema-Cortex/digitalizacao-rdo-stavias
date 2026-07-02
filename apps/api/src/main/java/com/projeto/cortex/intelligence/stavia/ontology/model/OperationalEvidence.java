package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.time.Instant;
import java.util.Map;

public record OperationalEvidence(
        String id,
        String entityId,
        String evidenceType,
        String sourceType,
        String sourceId,
        String description,
        String fileRef,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public OperationalEvidence {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("O ID da evidência operacional deve ser informado.");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("A entidade da evidência operacional deve ser informada.");
        }
        if (evidenceType == null || evidenceType.isBlank()) {
            throw new IllegalArgumentException("O tipo da evidência operacional deve ser informado.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A descrição da evidência operacional deve ser informada.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

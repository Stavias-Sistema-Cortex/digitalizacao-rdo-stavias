package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record OntologyRelation(
        String id,
        String sourceEntityId,
        String relationType,
        String targetEntityId,
        BigDecimal confidence,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public OntologyRelation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("O ID da relação ontológica deve ser informado.");
        }
        if (sourceEntityId == null || sourceEntityId.isBlank()) {
            throw new IllegalArgumentException("A origem da relação ontológica deve ser informada.");
        }
        if (targetEntityId == null || targetEntityId.isBlank()) {
            throw new IllegalArgumentException("O destino da relação ontológica deve ser informado.");
        }
        if (relationType == null || relationType.isBlank()) {
            throw new IllegalArgumentException("O tipo da relação ontológica deve ser informado.");
        }
        confidence = confidence == null ? BigDecimal.ONE : confidence;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

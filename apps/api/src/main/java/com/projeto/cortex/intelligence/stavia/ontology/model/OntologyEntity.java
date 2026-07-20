package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.time.Instant;
import java.util.Map;

public record OntologyEntity(
        String id,
        String entityType,
        String externalRefType,
        String externalRefId,
        String canonicalName,
        String description,
        String status,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public OntologyEntity {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("O ID da entidade ontológica deve ser informado.");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("O tipo da entidade ontológica deve ser informado.");
        }
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalArgumentException("O nome canônico da entidade ontológica deve ser informado.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

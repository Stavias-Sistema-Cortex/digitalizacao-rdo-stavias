package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEntity;
import java.time.Instant;
import java.util.Map;

public record OntologyEntityResponse(
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

    public static OntologyEntityResponse from(OntologyEntity entity) {
        return new OntologyEntityResponse(
                entity.id(),
                entity.entityType(),
                entity.externalRefType(),
                entity.externalRefId(),
                entity.canonicalName(),
                entity.description(),
                entity.status(),
                entity.metadata(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}

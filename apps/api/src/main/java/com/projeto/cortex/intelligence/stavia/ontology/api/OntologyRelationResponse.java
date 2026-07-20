package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyRelation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record OntologyRelationResponse(
        String id,
        String sourceEntityId,
        String relationType,
        String targetEntityId,
        BigDecimal confidence,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public static OntologyRelationResponse from(OntologyRelation relation) {
        return new OntologyRelationResponse(
                relation.id(),
                relation.sourceEntityId(),
                relation.relationType(),
                relation.targetEntityId(),
                relation.confidence(),
                relation.metadata(),
                relation.createdAt()
        );
    }
}

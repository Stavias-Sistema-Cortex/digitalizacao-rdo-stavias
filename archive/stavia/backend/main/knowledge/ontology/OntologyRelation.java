package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

import java.time.Instant;

public record OntologyRelation(
        String relationId,
        String originType,
        String originId,
        String originExternalCode,
        String originName,
        String originStatus,
        String relationType,
        String destinationType,
        String destinationId,
        String destinationExternalCode,
        String destinationName,
        String destinationStatus,
        String source,
        String observations,
        Instant updatedAt
) {
}

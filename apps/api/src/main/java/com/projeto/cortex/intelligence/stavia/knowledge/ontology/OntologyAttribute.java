package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

import java.math.BigDecimal;
import java.time.Instant;

public record OntologyAttribute(
        String evidenceId,
        String objectId,
        String entityType,
        String entityId,
        String objectName,
        String objectExternalCode,
        String fieldName,
        String fieldValue,
        String fieldType,
        String source,
        BigDecimal confidence,
        Instant updatedAt,
        Instant validAt
) {
}

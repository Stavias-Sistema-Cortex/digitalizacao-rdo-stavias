package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

import java.time.Instant;

public record OntologyObject(
        String objectId,
        String entityType,
        String entityTable,
        String entityId,
        String externalCode,
        String canonicalKey,
        String name,
        String status,
        String source,
        Instant createdAt,
        Instant updatedAt,
        Instant lastSeenAt
) {
}

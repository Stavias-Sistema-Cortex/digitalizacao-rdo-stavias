package com.projeto.cortex.ontology;

import java.time.Instant;

/**
 * Safe projection for Memory. Arbitrary payload/state JSON and person/device
 * identifiers are intentionally absent from this public record.
 */
public record OperationalMemoryEventResponse(
        String eventId,
        long commitSequence,
        String eventType,
        String source,
        String principalEntityType,
        String principalEntityId,
        String principalName,
        String worksiteId,
        String worksiteName,
        String rdoId,
        String rdoNumber,
        String serviceName,
        Instant occurredAt,
        Instant synchronizedAt,
        String origin,
        String syncStatus,
        int schemaVersion,
        String result,
        String errorCategory,
        double relevance
) {
}

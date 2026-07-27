package com.projeto.cortex.ontology;

import java.time.Instant;

/**
 * Safe projection for Memory. Arbitrary payload/state JSON and direct contact
 * data are intentionally absent. The authorized actor display name is exposed
 * only for the audit row, and device identifiers are owner-scoped by the query
 * service.
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
        String actorId,
        String actorName,
        String deviceId,
        String clientMutationId,
        String correlationId,
        String causationId,
        Long entityVersion,
        double relevance
) {
}

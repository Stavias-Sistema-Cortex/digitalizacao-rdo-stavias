package com.projeto.cortex.ontology;

import java.time.Instant;
import java.util.List;

public record OperationalMemoryPageResponse(
        List<OperationalMemoryEventResponse> events,
        Long nextBeforeCommitSeq,
        boolean hasMore,
        String scope,
        Instant serverTime
) {
}

package com.projeto.cortex.ontology;

import java.time.Instant;
import java.util.List;

public record OperationalMemoryPageResponse(
        List<OperationalMemoryEventResponse> items,
        OperationalMemoryCursor nextCursor,
        boolean hasMore,
        long highWaterMark,
        String scopeHash,
        OperationalMemoryCoverage coverage,
        Instant serverTime
) {
    public OperationalMemoryPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

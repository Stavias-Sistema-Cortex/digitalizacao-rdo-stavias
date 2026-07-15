package com.projeto.cortex.equipes;

import java.util.List;

public record EquipeHistoryPageResponse(
        long afterCommitSeq,
        long nextCommitSeq,
        boolean hasMore,
        int limit,
        List<EquipeHistoryResponse> events
) {
    public EquipeHistoryPageResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }
}

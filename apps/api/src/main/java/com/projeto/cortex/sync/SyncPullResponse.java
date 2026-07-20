package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record SyncPullResponse(
        long afterCommitSeq,
        long nextCommitSeq,
        int limit,
        boolean hasMore,
        Instant serverTimeUtc,
        List<EventoSync> eventos
) {

    public record EventoSync(
            long commitSeq,
            String id,
            String tipoEntidade,
            String entidadeId,
            String tipoEvento,
            String fonte,
            JsonNode payload,
            LocalDateTime ocorridoEmUtc,
            LocalDateTime criadoEmUtc,
            Long versaoEntidade
    ) {
    }
}

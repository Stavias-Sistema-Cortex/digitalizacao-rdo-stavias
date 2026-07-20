package com.projeto.cortex.sync;

import java.time.Instant;

public record SyncAckResponse(
        String dispositivoId,
        long ultimoEventoRecebidoCommitSeq,
        Instant serverTimeUtc
) {
}

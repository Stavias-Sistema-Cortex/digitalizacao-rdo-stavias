package com.projeto.cortex.sync;

import java.time.Instant;

public record SyncDeviceResponse(
        String id,
        String nome,
        String tipo,
        String usuarioId,
        boolean ativo,
        long ultimoEventoRecebidoCommitSeq,
        Instant serverTimeUtc
) {
}

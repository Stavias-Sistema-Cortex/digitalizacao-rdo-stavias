package com.projeto.cortex.sync;

public record SyncAckRequest(
        String dispositivoId,
        long ultimoEventoRecebidoCommitSeq
) {
}

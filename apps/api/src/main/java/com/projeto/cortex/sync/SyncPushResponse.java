package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record SyncPushResponse(
        String dispositivoId,
        Instant serverTimeUtc,
        List<ResultadoMutacao> resultados
) {

    public record ResultadoMutacao(
            String clientMutationId,
            String status,
            String entidadeTipo,
            String entidadeId,
            String operacao,
            Long eventoServidorCommitSeq,
            JsonNode resultado,
            JsonNode conflito,
            String erro
    ) {
    }
}

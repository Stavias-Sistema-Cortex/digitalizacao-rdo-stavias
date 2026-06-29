package com.projeto.cortex.sync;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record SyncPushRequest(
        String dispositivoId,
        List<MutacaoCliente> mutacoes
) {

    public record MutacaoCliente(
            String clientMutationId,
            String entidadeTipo,
            String entidadeId,
            String operacao,
            Long baseVersao,
            JsonNode payload,
            LocalDateTime criadaNoClienteEm
    ) {
    }
}

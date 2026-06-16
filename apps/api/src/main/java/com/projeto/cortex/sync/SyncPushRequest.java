package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

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

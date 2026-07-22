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
            LocalDateTime criadaNoClienteEm,
            String correlacaoId,
            FieldPatch fieldPatch,
            String actorId,
            List<String> authorizationScope,
            String ontologyEventId,
            String payloadHash,
            String causationId,
            List<String> dependsOnMutationIds
    ) {

        public MutacaoCliente(
                String clientMutationId,
                String entidadeTipo,
                String entidadeId,
                String operacao,
                Long baseVersao,
                JsonNode payload,
                LocalDateTime criadaNoClienteEm,
                String correlacaoId
        ) {
            this(
                    clientMutationId,
                    entidadeTipo,
                    entidadeId,
                    operacao,
                    baseVersao,
                    payload,
                    criadaNoClienteEm,
                    correlacaoId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    public record FieldPatch(
            JsonNode changed,
            JsonNode baseValues
    ) {
    }
}

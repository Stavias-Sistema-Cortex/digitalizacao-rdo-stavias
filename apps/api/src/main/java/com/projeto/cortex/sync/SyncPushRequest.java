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
            Integer schemaVersion,
            String deviceId,
            String userId,
            String obraId,
            String entityType,
            String entityId,
            String operation,
            Long baseVersion,
            List<String> changedFields,
            String occurredAt,
            MutationTrace trace,
            FieldPatch fieldPatch,
            List<RelatedEntity> relatedEntities,
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
                    null,
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

    public record MutationTrace(
            String actorId,
            String deviceId,
            List<String> authorizationScope,
            String correlationId,
            String causationId,
            String ontologyEventId,
            String payloadHash
    ) {
    }

    public record FieldPatch(
            JsonNode changed,
            JsonNode baseValues
    ) {
    }

    public record RelatedEntity(
            String tipo,
            String id,
            String nome
    ) {
    }
}

package com.projeto.cortex.financeiro.invoice.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncPushRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

final class FinanceSyncSupport {

    private FinanceSyncSupport() {
    }

    static String requireEntityId(
            SyncPushRequest.MutacaoCliente mutation,
            String label
    ) {
        if (mutation.entidadeId() == null || mutation.entidadeId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeId de " + label + " é obrigatório."
            );
        }
        return mutation.entidadeId().strip();
    }

    static void requireMatchingId(String payloadId, String entityId) {
        if (payloadId != null && !payloadId.isBlank()
                && !entityId.equals(payloadId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O id do payload difere do id da mutação."
            );
        }
    }

    static String worksite(
            JdbcTemplate jdbc,
            String table,
            String id,
            String label
    ) {
        try {
            return jdbc.queryForObject(
                    "SELECT obra_id FROM " + table
                            + " WHERE id = ? AND arquivado_em IS NULL",
                    String.class,
                    id
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    label + " não encontrado."
            );
        }
    }

    static String settlementWorksite(JdbcTemplate jdbc, String id) {
        try {
            return jdbc.queryForObject(
                    "SELECT obra_id FROM finance_liquidacao WHERE id = ?",
                    String.class,
                    id
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Liquidação não encontrada."
            );
        }
    }

    static long rowVersion(
            JdbcTemplate jdbc,
            String table,
            String id,
            boolean archivedColumn,
            String label
    ) {
        String sql = "SELECT versao_linha FROM " + table + " WHERE id = ?"
                + (archivedColumn ? " AND arquivado_em IS NULL" : "");
        Long version = jdbc.queryForObject(sql, Long.class, id);
        if (version == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    label + " não encontrado."
            );
        }
        return version;
    }

    static void requireSameWorksite(String requested, String stored) {
        if (requested == null || !requested.equals(stored)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A obra do registro financeiro não pode ser alterada."
            );
        }
    }

    static FinanceAuditContext audit(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        String correlation = mutation.correlacaoId() == null
                || mutation.correlacaoId().isBlank()
                ? mutation.clientMutationId()
                : mutation.correlacaoId().strip();
        return new FinanceAuditContext(
                context.actorId(), context.deviceId(), correlation, "OFFLINE"
        );
    }

    static <T> T value(
            ObjectMapper mapper,
            JsonNode payload,
            Class<T> type
    ) {
        try {
            if (payload == null || payload.isNull()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "payload é obrigatório."
                );
            }
            return mapper.treeToValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "payload financeiro inválido."
            );
        }
    }
}

package com.projeto.cortex.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class SyncService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SyncService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public SyncPullResponse pull(long afterCommitSeq, Integer requestedLimit) {
        if (afterCommitSeq < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "afterCommitSeq não pode ser negativo.");
        }

        int limit = normalizarLimit(requestedLimit);
        int queryLimit = limit + 1;

        List<SyncPullResponse.EventoSync> eventosComExtra = jdbcTemplate.query(
                """
                SELECT
                    commit_seq,
                    id,
                    tipo_entidade,
                    entidade_id,
                    tipo_evento,
                    fonte,
                    payload_json,
                    ocorrido_em,
                    criado_em
                FROM cortex_evento_operacional
                WHERE commit_seq > ?
                ORDER BY commit_seq
                LIMIT ?
                """,
                (rs, rowNum) -> new SyncPullResponse.EventoSync(
                        rs.getLong("commit_seq"),
                        rs.getString("id"),
                        rs.getString("tipo_entidade"),
                        rs.getString("entidade_id"),
                        rs.getString("tipo_evento"),
                        rs.getString("fonte"),
                        parseJson(rs.getString("payload_json")),
                        rs.getTimestamp("ocorrido_em").toLocalDateTime(),
                        rs.getTimestamp("criado_em").toLocalDateTime()
                ),
                afterCommitSeq,
                queryLimit
        );

        boolean hasMore = eventosComExtra.size() > limit;

        List<SyncPullResponse.EventoSync> eventos = hasMore
                ? new ArrayList<>(eventosComExtra.subList(0, limit))
                : eventosComExtra;

        long nextCommitSeq = eventos.isEmpty()
                ? afterCommitSeq
                : eventos.get(eventos.size() - 1).commitSeq();

        return new SyncPullResponse(
                afterCommitSeq,
                nextCommitSeq,
                limit,
                hasMore,
                Instant.now(),
                eventos
        );
    }

    private int normalizarLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }

        if (requestedLimit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit precisa ser positivo.");
        }

        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private JsonNode parseJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.createObjectNode();
            }

            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Evento operacional possui payload_json inválido.", exception);
        }
    }
}

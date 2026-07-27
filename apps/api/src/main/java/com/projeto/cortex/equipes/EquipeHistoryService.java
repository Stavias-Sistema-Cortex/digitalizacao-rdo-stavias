package com.projeto.cortex.equipes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class EquipeHistoryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EquipeService equipeService;

    public EquipeHistoryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EquipeService equipeService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.equipeService = equipeService;
    }

    public EquipeHistoryPageResponse listar(
            String equipeId,
            Long requestedAfterCommitSeq,
            Integer requestedLimit
    ) {
        EquipeResponse team = equipeService.buscarPorId(equipeId);
        long afterCommitSeq = requestedAfterCommitSeq == null ? 0 : requestedAfterCommitSeq;
        if (afterCommitSeq < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "afterCommitSeq não pode ser negativo."
            );
        }
        int limit = normalizeLimit(requestedLimit);

        List<EquipeHistoryResponse> rows = jdbcTemplate.query(
                """
                SELECT
                    ev.commit_seq,
                    ev.id,
                    ev.tipo_entidade,
                    ev.entidade_id,
                    ev.tipo_evento,
                    ev.fonte,
                    ev.obra_id,
                    ev.colaborador_id,
                    ev.ocorrido_em,
                    ev.criado_em,
                    ev.versao_entidade,
                    ev.payload_json
                FROM cortex_evento_operacional ev
                WHERE ev.commit_seq > ?
                  AND (
                      (ev.tipo_entidade = 'EQUIPE' AND ev.entidade_id = ?)
                      OR (
                          ev.tipo_entidade IN (
                              'PARTICIPACAO_EQUIPE',
                              'ALOCACAO_EQUIPE_OBRA'
                          )
                          AND EXISTS (
                              SELECT 1
                              FROM jsonb_array_elements(
                                  COALESCE(ev.entidades_relacionadas_json, '[]'::jsonb)
                              ) AS related
                              WHERE related ->> 'id' = ?
                          )
                      )
                  )
                ORDER BY ev.commit_seq
                LIMIT ?
                """,
                (rs, rowNum) -> new EquipeHistoryResponse(
                        rs.getLong("commit_seq"),
                        rs.getString("id"),
                        rs.getString("tipo_entidade"),
                        rs.getString("entidade_id"),
                        rs.getString("tipo_evento"),
                        rs.getString("fonte"),
                        rs.getString("obra_id"),
                        rs.getString("colaborador_id"),
                        toLocalDateTime(rs.getTimestamp("ocorrido_em")),
                        toLocalDateTime(rs.getTimestamp("criado_em")),
                        rs.getLong("versao_entidade"),
                        parseJson(rs.getString("payload_json"))
                ),
                afterCommitSeq,
                team.id(),
                team.id(),
                limit + 1
        );

        boolean hasMore = rows.size() > limit;
        List<EquipeHistoryResponse> events = hasMore
                ? new ArrayList<>(rows.subList(0, limit))
                : rows;
        long next = events.isEmpty()
                ? afterCommitSeq
                : events.getLast().commitSeq();
        return new EquipeHistoryPageResponse(
                afterCommitSeq,
                next,
                hasMore,
                limit,
                events
        );
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit precisa ser positivo.");
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private JsonNode parseJson(String value) {
        try {
            return value == null || value.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Evento de equipe contém JSON inválido.", exception);
        }
    }

    private LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}

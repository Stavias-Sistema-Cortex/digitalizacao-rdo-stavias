package com.projeto.cortex.memory;

import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CortexOperationalMemoryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CortexOperationalMemoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long registrarEvento(
            String tipoEntidade,
            String entidadeId,
            String tipoEvento,
            String fonte,
            Map<String, Object> payload
    ) {
        long commitSeq = proximaCommitSeq();

        String eventoId = UUID.randomUUID().toString();
        String payloadJson = toJson(payload);

        jdbcTemplate.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id,
                    commit_seq,
                    tipo_entidade,
                    entidade_id,
                    tipo_evento,
                    fonte,
                    payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                eventoId,
                commitSeq,
                tipoEntidade,
                entidadeId,
                tipoEvento,
                fonte,
                payloadJson
        );

        Long sequencia = jdbcTemplate.queryForObject(
                """
                SELECT sequencia
                FROM cortex_evento_operacional
                WHERE id = ?
                """,
                Long.class,
                eventoId
        );

        if (sequencia == null) {
            throw new IllegalStateException("Evento operacional foi criado sem sequência.");
        }

        jdbcTemplate.update(
                """
                INSERT INTO cortex_estado_entidade (
                    tipo_entidade,
                    entidade_id,
                    versao_entidade,
                    ultimo_evento_seq
                ) VALUES (?, ?, 1, ?)
                ON DUPLICATE KEY UPDATE
                    versao_entidade = versao_entidade + 1,
                    ultimo_evento_seq = VALUES(ultimo_evento_seq)
                """,
                tipoEntidade,
                entidadeId,
                sequencia
        );

        return commitSeq;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarObjeto(
            String tipoEntidade,
            String entidadeId,
            String codigoExterno,
            String nome,
            String status,
            String fonte
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO cortex_objeto (
                    id,
                    tipo_entidade,
                    entidade_id,
                    codigo_externo,
                    nome,
                    status,
                    fonte
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    codigo_externo = VALUES(codigo_externo),
                    nome = VALUES(nome),
                    status = VALUES(status),
                    fonte = VALUES(fonte),
                    versao_linha = versao_linha + 1
                """,
                UUID.randomUUID().toString(),
                tipoEntidade,
                entidadeId,
                codigoExterno,
                nome,
                status,
                fonte
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void substituirRelacaoAtiva(
            String origemTipo,
            String origemId,
            String destinoTipo,
            String destinoId,
            String tipoRelacao,
            String fonte,
            String observacoes
    ) {
        jdbcTemplate.update(
                """
                UPDATE cortex_relacao
                SET
                    ativa = 0,
                    encerrado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE origem_tipo = ?
                  AND origem_id = ?
                  AND tipo_relacao = ?
                  AND ativa = 1
                  AND NOT (destino_tipo = ? AND destino_id = ?)
                """,
                origemTipo,
                origemId,
                tipoRelacao,
                destinoTipo,
                destinoId
        );

        registrarRelacaoAtiva(
                origemTipo,
                origemId,
                destinoTipo,
                destinoId,
                tipoRelacao,
                fonte,
                observacoes
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarRelacaoAtiva(
            String origemTipo,
            String origemId,
            String destinoTipo,
            String destinoId,
            String tipoRelacao,
            String fonte,
            String observacoes
    ) {
        if (destinoId == null || destinoId.isBlank()) {
            return;
        }

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO cortex_relacao (
                        id,
                        origem_tipo,
                        origem_id,
                        destino_tipo,
                        destino_id,
                        tipo_relacao,
                        ativa,
                        fonte,
                        observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    origemTipo,
                    origemId,
                    destinoTipo,
                    destinoId,
                    tipoRelacao,
                    fonte,
                    observacoes
            );
        } catch (DuplicateKeyException ignored) {
            // Relação ativa já existe. Isso mantém a operação idempotente.
        }
    }

    private long proximaCommitSeq() {
        int linhasAtualizadas = jdbcTemplate.update(
                """
                UPDATE cortex_evento_commit_sequence
                SET ultima_commit_seq = LAST_INSERT_ID(ultima_commit_seq + 1)
                WHERE id = 1
                """
        );

        if (linhasAtualizadas != 1) {
            throw new IllegalStateException(
                    "Sequência de commit do córtex não inicializada (linha id=1 ausente)."
            );
        }

        Long commitSeq = jdbcTemplate.queryForObject(
                "SELECT LAST_INSERT_ID()",
                Long.class
        );

        if (commitSeq == null) {
            throw new IllegalStateException("Não foi possível gerar commit_seq.");
        }

        return commitSeq;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Payload inválido para evento operacional.", exception);
        }
    }
}

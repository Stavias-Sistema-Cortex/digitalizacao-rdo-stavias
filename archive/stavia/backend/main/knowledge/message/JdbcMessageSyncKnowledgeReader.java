package com.projeto.cortex.intelligence.stavia.knowledge.message;

import com.projeto.cortex.mensagens.domain.MessageWorksiteAccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMessageSyncKnowledgeReader
        implements MessageSyncKnowledgeReader {

    private final JdbcTemplate jdbcTemplate;
    private final MessageWorksiteAccessService accessService;

    public JdbcMessageSyncKnowledgeReader(
            JdbcTemplate jdbcTemplate,
            MessageWorksiteAccessService accessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    @Override
    public List<PendingDocument> pendingDocuments(
            String worksiteId,
            String userId
    ) {
        if (!hasText(worksiteId)
                || !hasText(userId)
                || !accessService.canRead(userId, worksiteId)) {
            return List.of();
        }

        boolean globalReader = accessService.isGlobalReader(userId);
        return jdbcTemplate.query(
                """
                SELECT
                    ma.id AS attachment_id,
                    m.id AS message_id,
                    c.id AS conversation_id,
                    so.id AS stored_object_id,
                    so.status AS storage_status,
                    sm.id AS sync_receipt_id,
                    sm.status AS sync_status,
                    sm.erro_categoria AS sync_error_category,
                    GREATEST(
                        ma.atualizado_em,
                        m.atualizado_em,
                        c.atualizado_em,
                        so.criado_em,
                        COALESCE(sm.atualizado_em, so.criado_em)
                    ) AS freshness_at
                FROM mensagem_anexo ma
                JOIN mensagem m
                  ON m.id = ma.mensagem_id
                JOIN conversa c
                  ON c.id = m.conversa_id
                JOIN stored_object so
                  ON so.id = ma.stored_object_id
                LEFT JOIN sync_mutacao_cliente sm
                  ON sm.entidade_tipo = 'MENSAGEM_ANEXO'
                 AND sm.entidade_id = ma.id
                WHERE c.obra_id = ?
                  AND c.status = 'ATIVA'
                  AND c.deletado_em IS NULL
                  AND m.status <> 'EXCLUIDA'
                  AND m.deletado_em IS NULL
                  AND ma.status = 'ATIVO'
                  AND ma.deletado_em IS NULL
                  AND (
                      ? = 1
                      OR (
                          EXISTS (
                              SELECT 1
                              FROM conversa_participante cp
                              WHERE cp.conversa_id = c.id
                                AND cp.colaborador_id = ?
                                AND cp.status = 'ATIVO'
                                AND cp.removido_em IS NULL
                                AND cp.deletado_em IS NULL
                          )
                          AND (
                              c.tipo <> 'EQUIPE'
                              OR EXISTS (
                                  SELECT 1
                                  FROM equipe e
                                  JOIN equipe_membro em ON em.equipe_id = e.id
                                  WHERE e.id = c.equipe_id
                                    AND e.obra_id = c.obra_id
                                    AND e.status = 'ATIVA'
                                    AND e.deletado_em IS NULL
                                    AND em.colaborador_id = ?
                                    AND em.status = 'ATIVO'
                                    AND em.removido_em IS NULL
                                    AND em.deletado_em IS NULL
                              )
                          )
                      )
                  )
                  AND (
                      so.status <> 'DISPONIVEL'
                      OR sm.status IN ('PENDENTE', 'ERRO')
                  )
                ORDER BY freshness_at DESC, ma.id, sm.id
                """,
                (rs, row) -> new PendingDocument(
                        rs.getString("attachment_id"),
                        rs.getString("message_id"),
                        rs.getString("conversation_id"),
                        rs.getString("stored_object_id"),
                        rs.getString("storage_status"),
                        rs.getString("sync_receipt_id"),
                        rs.getString("sync_status"),
                        rs.getString("sync_error_category"),
                        toInstant(rs.getTimestamp("freshness_at"))
                ),
                worksiteId.trim(),
                globalReader ? 1 : 0,
                userId.trim(),
                userId.trim()
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

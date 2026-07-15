package com.projeto.cortex.mensagens;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class ConversationScopeRepository {

    private final JdbcTemplate jdbcTemplate;

    ConversationScopeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<ConversationScope> findConversation(String conversationId) {
        return jdbcTemplate.query(
                """
                SELECT id, tipo, titulo, obra_id, equipe_id, status
                FROM conversa
                WHERE id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new ConversationScope(
                        rs.getString("id"),
                        rs.getString("tipo"),
                        rs.getString("titulo"),
                        rs.getString("obra_id"),
                        rs.getString("equipe_id"),
                        rs.getString("status")
                ),
                conversationId
        ).stream().findFirst();
    }

    boolean hasActiveParticipant(String conversationId, String collaboratorId) {
        Integer exists = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM conversa_participante
                    WHERE conversa_id = ?
                      AND colaborador_id = ?
                      AND status = 'ATIVO'
                      AND saiu_em IS NULL
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                conversationId,
                collaboratorId
        );
        return exists != null && exists == 1;
    }

    List<String> activeTeamWorksiteIds(String teamId) {
        return jdbcTemplate.queryForList(
                """
                SELECT obra_id
                FROM equipe_obra
                WHERE equipe_id = ?
                  AND status = 'ATIVO'
                  AND fim_em IS NULL
                ORDER BY inicio_em, id
                """,
                String.class,
                teamId
        );
    }

    Optional<String> findConversationIdByMessage(String messageId) {
        return jdbcTemplate.queryForList(
                """
                SELECT conversa_id
                FROM mensagem
                WHERE id = ?
                LIMIT 1
                """,
                String.class,
                messageId
        ).stream().findFirst();
    }

    Optional<String> findConversationIdByAttachment(String attachmentId) {
        return jdbcTemplate.queryForList(
                """
                SELECT mensagem.conversa_id
                FROM mensagem_anexo anexo
                JOIN mensagem ON mensagem.id = anexo.mensagem_id
                WHERE anexo.id = ?
                  AND anexo.removido_em IS NULL
                LIMIT 1
                """,
                String.class,
                attachmentId
        ).stream().findFirst();
    }
}

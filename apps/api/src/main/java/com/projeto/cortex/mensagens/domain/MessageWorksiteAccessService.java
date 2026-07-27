package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Derives worksite-scoped messaging access from the same active conversation
 * and team memberships enforced by the messaging API.
 */
@Service
public class MessageWorksiteAccessService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public MessageWorksiteAccessService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public boolean canRead(String userId, String worksiteId) {
        if (!hasText(userId)
                || !hasText(worksiteId)
                || !currentUserService.podeAcessarObra(userId, worksiteId)) {
            return false;
        }
        if (currentUserService.isAlfa(userId)) {
            return true;
        }

        Integer allowed = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM conversa c
                    JOIN conversa_participante cp
                      ON cp.conversa_id = c.id
                     AND cp.colaborador_id = ?
                     AND cp.status = 'ATIVO'
                     AND cp.removido_em IS NULL
                     AND cp.deletado_em IS NULL
                    WHERE c.obra_id = ?
                      AND c.status = 'ATIVA'
                      AND c.deletado_em IS NULL
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
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                userId.trim(),
                worksiteId.trim(),
                userId.trim()
        );
        return allowed != null && allowed == 1;
    }

    public boolean isGlobalReader(String userId) {
        return hasText(userId) && currentUserService.isAlfa(userId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversaAccessPolicy {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public ConversaAccessPolicy(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public ConversationScope requireAccess(String conversationId) {
        ConversationScope scope = requireExisting(conversationId);
        String userId = currentUserService.requireUserId();
        /*
         * Conversa direta é de duas pessoas, e de mais ninguém.
         *
         * <p>Alfa alcança tudo o que é registro de obra — conversa de obra, de
         * equipe, de grupo de trabalho — porque isso é documentação do
         * empreendimento e alguém precisa responder por ela. Conversa direta
         * não é: é correspondência particular entre dois colaboradores, e o
         * papel administrativo não dá a ninguém o direito de abri-la.
         *
         * <p>Sem esta porta fechada, quem tem Alfa lia a caixa de mensagens de
         * qualquer pessoa da empresa, e as duas pontas da conversa não tinham
         * como saber. É a única regra aqui que protege gente, e não dado.
         */
        boolean alfa = currentUserService.isAlfa(userId);
        if (alfa && scope.type() != ConversationType.DIRETA) {
            return scope;
        }
        if (!isActiveParticipant(scope.id(), userId)) {
            throw forbidden();
        }
        if (alfa) {
            return scope;
        }

        switch (scope.type()) {
            case DIRETA, GRUPO -> {
                return scope;
            }
            case OBRA -> {
                if (currentUserService.podeAcessarObra(userId, scope.obraId())) {
                    return scope;
                }
            }
            case EQUIPE -> {
                if (currentUserService.podeAcessarObra(userId, scope.obraId())
                        && isActiveTeamMember(scope, userId)) {
                    return scope;
                }
            }
        }
        throw forbidden();
    }

    public ConversationScope requireAdmin(String conversationId) {
        ConversationScope scope = requireAccess(conversationId);
        String userId = currentUserService.requireUserId();
        if (currentUserService.isAlfa(userId)) {
            return scope;
        }

        Integer allowed = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM conversa_participante
                    WHERE conversa_id = ?
                      AND colaborador_id = ?
                      AND papel = 'ADMIN'
                      AND status = 'ATIVO'
                      AND removido_em IS NULL
                      AND deletado_em IS NULL
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                scope.id(),
                userId
        );
        if (allowed != null && allowed == 1) {
            return scope;
        }
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Somente administradores da conversa podem alterar participantes."
        );
    }

    public ConversationScope requireExisting(String conversationId) {
        if (conversationId == null || conversationId.isBlank()
                || conversationId.length() > 120) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de conversa inválido."
            );
        }

        ConversationScope scope = jdbcTemplate.query(
                """
                SELECT id, tipo, obra_id, equipe_id, criado_por, status
                FROM conversa
                WHERE id = ?
                  AND status = 'ATIVA'
                  AND deletado_em IS NULL
                LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? new ConversationScope(
                                resultSet.getString("id"),
                                ConversationType.valueOf(
                                        resultSet.getString("tipo")
                                ),
                                resultSet.getString("obra_id"),
                                resultSet.getString("equipe_id"),
                                resultSet.getString("criado_por"),
                                resultSet.getString("status")
                        )
                        : null,
                conversationId.strip()
        );

        if (scope == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Conversa não encontrada."
            );
        }
        return scope;
    }

    public boolean isActiveParticipant(String conversationId, String userId) {
        Integer allowed = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM conversa_participante
                    WHERE conversa_id = ?
                      AND colaborador_id = ?
                      AND status = 'ATIVO'
                      AND removido_em IS NULL
                      AND deletado_em IS NULL
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                conversationId,
                userId
        );
        return allowed != null && allowed == 1;
    }

    private boolean isActiveTeamMember(
            ConversationScope scope,
            String userId
    ) {
        Integer allowed = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM equipe e
                    JOIN equipe_membro em ON em.equipe_id = e.id
                    WHERE e.id = ?
                      AND e.obra_id = ?
                      AND e.status = 'ATIVA'
                      AND e.deletado_em IS NULL
                      AND em.colaborador_id = ?
                      AND em.status = 'ATIVO'
                      AND em.removido_em IS NULL
                      AND em.deletado_em IS NULL
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                scope.teamId(),
                scope.obraId(),
                userId
        );
        return allowed != null && allowed == 1;
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não participa desta conversa ou seu vínculo não está ativo."
        );
    }
}

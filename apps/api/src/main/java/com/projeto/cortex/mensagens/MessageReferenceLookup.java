package com.projeto.cortex.mensagens;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
class MessageReferenceLookup {

    private final JdbcTemplate jdbcTemplate;

    MessageReferenceLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<String> worksiteForKnownObject(String objectType, String objectId) {
        String sql = switch (objectType) {
            case "OBRA" -> "SELECT id FROM obra WHERE id = ? LIMIT 1";
            case "RDO" -> "SELECT obra_id FROM rdo WHERE id = ? LIMIT 1";
            case "PROGRAMACAO" -> """
                    SELECT obra_id
                    FROM programacao_operacional
                    WHERE id = ?
                    LIMIT 1
                    """;
            case "EVENTO" -> """
                    SELECT obra_id
                    FROM cortex_evento_operacional
                    WHERE id = ?
                      AND obra_id IS NOT NULL
                    LIMIT 1
                    """;
            default -> throw new IllegalArgumentException(
                    "Tipo de referência conhecido não suportado: " + objectType
            );
        };
        return jdbcTemplate.queryForList(sql, String.class, objectId)
                .stream()
                .findFirst();
    }

    boolean teamActiveAtWorksite(String teamId, String worksiteId) {
        return exists(
                """
                SELECT COUNT(*)
                FROM equipe_obra
                WHERE equipe_id = ?
                  AND obra_id = ?
                  AND status = 'ATIVO'
                  AND fim_em IS NULL
                """,
                teamId,
                worksiteId
        );
    }

    boolean activeConversationParticipant(String conversationId, String collaboratorId) {
        return exists(
                """
                SELECT COUNT(*)
                FROM conversa_participante
                WHERE conversa_id = ?
                  AND colaborador_id = ?
                  AND status = 'ATIVO'
                  AND saiu_em IS NULL
                """,
                conversationId,
                collaboratorId
        );
    }

    boolean ontologyObjectRelatedToWorksite(
            String objectType,
            String objectId,
            String worksiteId
    ) {
        return exists(
                """
                SELECT COUNT(*)
                FROM cortex_objeto object
                WHERE object.tipo_entidade = ?
                  AND object.entidade_id = ?
                  AND (
                    EXISTS (
                        SELECT 1
                        FROM cortex_relacao relation_out
                        WHERE relation_out.origem_tipo = object.tipo_entidade
                          AND relation_out.origem_id = object.entidade_id
                          AND relation_out.destino_tipo = 'OBRA'
                          AND relation_out.destino_id = ?
                          AND relation_out.ativa = 1
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM cortex_relacao relation_in
                        WHERE relation_in.destino_tipo = object.tipo_entidade
                          AND relation_in.destino_id = object.entidade_id
                          AND relation_in.origem_tipo = 'OBRA'
                          AND relation_in.origem_id = ?
                          AND relation_in.ativa = 1
                    )
                  )
                """,
                objectType,
                objectId,
                worksiteId,
                worksiteId
        );
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }
}

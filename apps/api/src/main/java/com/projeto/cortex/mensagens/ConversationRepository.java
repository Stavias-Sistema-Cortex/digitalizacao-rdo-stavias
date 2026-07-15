package com.projeto.cortex.mensagens;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<ConversaResponse> findById(String conversationId) {
        List<ConversaResponse> conversations = jdbcTemplate.query(
                """
                SELECT
                    c.id,
                    c.tipo,
                    c.titulo,
                    c.obra_id,
                    o.nome AS obra_nome,
                    c.equipe_id,
                    e.nome AS equipe_nome,
                    c.criado_por,
                    c.status,
                    c.ultima_atividade_em,
                    c.criado_em,
                    c.atualizado_em,
                    c.versao_linha
                FROM conversa c
                LEFT JOIN obra o ON o.id = c.obra_id
                LEFT JOIN equipe e ON e.id = c.equipe_id
                WHERE c.id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new ConversaResponse(
                        rs.getString("id"),
                        rs.getString("tipo"),
                        rs.getString("titulo"),
                        rs.getString("obra_id"),
                        rs.getString("obra_nome"),
                        rs.getString("equipe_id"),
                        rs.getString("equipe_nome"),
                        rs.getString("criado_por"),
                        rs.getString("status"),
                        local(rs.getTimestamp("ultima_atividade_em")),
                        local(rs.getTimestamp("criado_em")),
                        local(rs.getTimestamp("atualizado_em")),
                        rs.getLong("versao_linha"),
                        List.of()
                ),
                conversationId
        );
        if (conversations.isEmpty()) {
            return Optional.empty();
        }

        ConversaResponse conversation = conversations.getFirst();
        return Optional.of(new ConversaResponse(
                conversation.id(),
                conversation.tipo(),
                conversation.titulo(),
                conversation.obraId(),
                conversation.obraNome(),
                conversation.equipeId(),
                conversation.equipeNome(),
                conversation.criadoPor(),
                conversation.status(),
                conversation.ultimaAtividadeEm(),
                conversation.criadoEm(),
                conversation.atualizadoEm(),
                conversation.versaoEntidade(),
                findParticipants(conversationId)
        ));
    }

    ConversaPageResponse list(
            ConversaFilter filter,
            String userId,
            Optional<Set<String>> allowedWorksites
    ) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        List<Object> filterParams = new ArrayList<>();

        if (allowedWorksites.isPresent()) {
            Set<String> ids = allowedWorksites.orElseThrow();
            where.append("""
                    AND EXISTS (
                        SELECT 1
                        FROM conversa_participante own_participation
                        WHERE own_participation.conversa_id = c.id
                          AND own_participation.colaborador_id = ?
                          AND own_participation.status = 'ATIVO'
                          AND own_participation.saiu_em IS NULL
                    )
                    """);
            filterParams.add(userId);

            String placeholders = placeholders(ids.size());
            where.append(" AND (c.obra_id IN (")
                    .append(placeholders)
                    .append(") OR (c.obra_id IS NULL AND c.equipe_id IS NOT NULL AND EXISTS (")
                    .append("SELECT 1 FROM equipe_obra eo WHERE eo.equipe_id = c.equipe_id ")
                    .append("AND eo.status = 'ATIVO' AND eo.fim_em IS NULL AND eo.obra_id IN (")
                    .append(placeholders)
                    .append(")) OR (c.obra_id IS NULL AND c.equipe_id IS NULL)) ");
            filterParams.addAll(ids);
            filterParams.addAll(ids);
        }

        if (hasText(filter.obraId())) {
            where.append(" AND c.obra_id = ? ");
            filterParams.add(filter.obraId().trim());
        }
        if (hasText(filter.equipeId())) {
            where.append(" AND c.equipe_id = ? ");
            filterParams.add(filter.equipeId().trim());
        }
        if (hasText(filter.texto())) {
            String search = "%" + filter.texto().trim().toLowerCase() + "%";
            where.append("""
                    AND (
                        LOWER(COALESCE(c.titulo, '')) LIKE ?
                        OR LOWER(COALESCE(o.nome, '')) LIKE ?
                        OR LOWER(COALESCE(e.nome, '')) LIKE ?
                        OR EXISTS (
                            SELECT 1
                            FROM conversa_participante search_participation
                            JOIN colaborador search_collaborator
                              ON search_collaborator.id = search_participation.colaborador_id
                            WHERE search_participation.conversa_id = c.id
                              AND search_participation.status = 'ATIVO'
                              AND LOWER(search_collaborator.nome) LIKE ?
                        )
                    )
                    """);
            filterParams.add(search);
            filterParams.add(search);
            filterParams.add(search);
            filterParams.add(search);
        }

        Long total = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM conversa c
                LEFT JOIN obra o ON o.id = c.obra_id
                LEFT JOIN equipe e ON e.id = c.equipe_id
                """ + where,
                Long.class,
                filterParams.toArray()
        );

        List<Object> itemParams = new ArrayList<>();
        itemParams.add(userId);
        itemParams.add(userId);
        itemParams.addAll(filterParams);
        itemParams.add(filter.size());
        itemParams.add((filter.page() - 1) * filter.size());

        List<ConversaSummaryResponse> items = jdbcTemplate.query(
                """
                SELECT
                    c.id,
                    c.tipo,
                    c.titulo,
                    c.obra_id,
                    o.nome AS obra_nome,
                    c.equipe_id,
                    e.nome AS equipe_nome,
                    c.status,
                    c.ultima_atividade_em,
                    last_message.id AS ultima_mensagem_id,
                    LEFT(last_message.texto, 140) AS ultima_mensagem_previa,
                    last_message.criada_servidor_em AS ultima_mensagem_em,
                    (
                        SELECT COUNT(*)
                        FROM mensagem unread_message
                        WHERE unread_message.conversa_id = c.id
                          AND unread_message.estado <> 'REMOVIDA'
                          AND unread_message.remetente_id <> ?
                          AND unread_message.criada_servidor_em > COALESCE((
                              SELECT own_read.ultima_leitura_em
                              FROM conversa_participante own_read
                              WHERE own_read.conversa_id = c.id
                                AND own_read.colaborador_id = ?
                                AND own_read.status = 'ATIVO'
                              LIMIT 1
                          ), TIMESTAMP('1970-01-01 00:00:00'))
                    ) AS nao_lidas,
                    c.versao_linha
                FROM conversa c
                LEFT JOIN obra o ON o.id = c.obra_id
                LEFT JOIN equipe e ON e.id = c.equipe_id
                LEFT JOIN mensagem last_message ON last_message.id = (
                    SELECT latest.id
                    FROM mensagem latest
                    WHERE latest.conversa_id = c.id
                      AND latest.estado <> 'REMOVIDA'
                    ORDER BY
                        latest.enviada_cliente_em DESC,
                        latest.criada_servidor_em DESC,
                        latest.id DESC
                    LIMIT 1
                )
                """ + where + """
                ORDER BY c.ultima_atividade_em DESC, c.id
                LIMIT ? OFFSET ?
                """,
                summaryMapper(),
                itemParams.toArray()
        );

        return new ConversaPageResponse(
                items,
                filter.page(),
                filter.size(),
                total == null ? 0 : total
        );
    }

    boolean worksiteExists(String worksiteId) {
        return exists(
                "SELECT COUNT(*) FROM obra WHERE id = ? AND arquivado_em IS NULL",
                worksiteId
        );
    }

    boolean teamActiveAtWorksite(String teamId, String worksiteId) {
        return exists(
                """
                SELECT COUNT(*)
                FROM equipe e
                JOIN equipe_obra eo ON eo.equipe_id = e.id
                WHERE e.id = ?
                  AND e.status = 'ATIVA'
                  AND eo.obra_id = ?
                  AND eo.status = 'ATIVO'
                  AND eo.fim_em IS NULL
                """,
                teamId,
                worksiteId
        );
    }

    List<String> activeTeamMemberIds(String teamId) {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT em.colaborador_id
                FROM equipe_membro em
                JOIN colaborador c ON c.id = em.colaborador_id
                WHERE em.equipe_id = ?
                  AND em.status = 'ATIVO'
                  AND em.fim_em IS NULL
                  AND c.ativo = 1
                  AND c.deletado_em IS NULL
                ORDER BY em.colaborador_id
                """,
                String.class,
                teamId
        );
    }

    Set<String> activeCollaboratorIds(Set<String> collaboratorIds) {
        if (collaboratorIds == null || collaboratorIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                """
                SELECT id
                FROM colaborador
                WHERE ativo = 1
                  AND deletado_em IS NULL
                  AND id IN (%s)
                """.formatted(placeholders(collaboratorIds.size())),
                String.class,
                collaboratorIds.toArray()
        ));
    }

    void insertConversation(
            ConversaCreateRequest request,
            String actorId,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO conversa (
                    id, tipo, titulo, obra_id, equipe_id, criado_por,
                    status, ultima_atividade_em, criado_em, atualizado_em,
                    versao_linha
                ) VALUES (?, ?, ?, ?, ?, ?, 'ATIVA', ?, ?, ?, 1)
                """,
                request.id(),
                request.tipo(),
                request.titulo(),
                request.obraId(),
                request.equipeId(),
                actorId,
                now,
                now,
                now
        );
    }

    void insertParticipant(
            String conversationId,
            String collaboratorId,
            String role,
            String actorId,
            LocalDateTime joinedAt
    ) {
        insertParticipant(
                UUID.randomUUID().toString(),
                conversationId,
                collaboratorId,
                role,
                joinedAt,
                actorId
        );
    }

    void insertParticipant(
            String participantId,
            String conversationId,
            String collaboratorId,
            String role,
            LocalDateTime joinedAt,
            String actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO conversa_participante (
                    id, conversa_id, colaborador_id, papel, status,
                    entrou_em, adicionado_por, versao_linha
                ) VALUES (?, ?, ?, ?, 'ATIVO', ?, ?, 1)
                """,
                participantId,
                conversationId,
                collaboratorId,
                role,
                joinedAt,
                actorId
        );
    }

    int updateConversation(
            String conversationId,
            String title,
            String status,
            long baseVersion,
            LocalDateTime now
    ) {
        return jdbcTemplate.update(
                """
                UPDATE conversa
                SET titulo = ?,
                    status = ?,
                    arquivada_em = CASE
                        WHEN ? = 'ARQUIVADA' THEN COALESCE(arquivada_em, ?)
                        ELSE arquivada_em
                    END,
                    atualizado_em = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                """,
                title,
                status,
                status,
                now,
                now,
                conversationId,
                baseVersion
        );
    }

    Optional<ConversaParticipantResponse> findActiveParticipant(
            String conversationId,
            String collaboratorId
    ) {
        return jdbcTemplate.query(
                participantSelect() + """
                WHERE cp.conversa_id = ?
                  AND cp.colaborador_id = ?
                  AND cp.status = 'ATIVO'
                  AND cp.saiu_em IS NULL
                LIMIT 1
                """,
                participantMapper(),
                conversationId,
                collaboratorId
        ).stream().findFirst();
    }

    Optional<ConversaParticipantResponse> findParticipantById(String participantId) {
        return jdbcTemplate.query(
                participantSelect() + " WHERE cp.id = ? LIMIT 1",
                participantMapper(),
                participantId
        ).stream().findFirst();
    }

    int endParticipant(
            String participantId,
            long baseVersion,
            String actorId,
            LocalDateTime endedAt
    ) {
        return jdbcTemplate.update(
                """
                UPDATE conversa_participante
                SET status = 'REMOVIDO',
                    saiu_em = ?,
                    removido_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'ATIVO'
                  AND saiu_em IS NULL
                  AND versao_linha = ?
                """,
                endedAt,
                actorId,
                participantId,
                baseVersion
        );
    }

    private List<ConversaParticipantResponse> findParticipants(String conversationId) {
        return jdbcTemplate.query(
                participantSelect() + """
                WHERE cp.conversa_id = ?
                ORDER BY
                    CASE WHEN cp.status = 'ATIVO' THEN 0 ELSE 1 END,
                    cp.entrou_em,
                    cp.id
                """,
                participantMapper(),
                conversationId
        );
    }

    private String participantSelect() {
        return """
                SELECT
                    cp.id,
                    cp.conversa_id,
                    cp.colaborador_id,
                    c.nome AS colaborador_nome,
                    cp.papel,
                    cp.status,
                    cp.entrou_em,
                    cp.saiu_em,
                    cp.ultima_leitura_em,
                    cp.versao_linha
                FROM conversa_participante cp
                JOIN colaborador c ON c.id = cp.colaborador_id
                """;
    }

    private RowMapper<ConversaParticipantResponse> participantMapper() {
        return (rs, rowNum) -> new ConversaParticipantResponse(
                rs.getString("id"),
                rs.getString("conversa_id"),
                rs.getString("colaborador_id"),
                rs.getString("colaborador_nome"),
                rs.getString("papel"),
                rs.getString("status"),
                local(rs.getTimestamp("entrou_em")),
                local(rs.getTimestamp("saiu_em")),
                local(rs.getTimestamp("ultima_leitura_em")),
                rs.getLong("versao_linha")
        );
    }

    private RowMapper<ConversaSummaryResponse> summaryMapper() {
        return (rs, rowNum) -> new ConversaSummaryResponse(
                rs.getString("id"),
                rs.getString("tipo"),
                rs.getString("titulo"),
                rs.getString("obra_id"),
                rs.getString("obra_nome"),
                rs.getString("equipe_id"),
                rs.getString("equipe_nome"),
                rs.getString("status"),
                local(rs.getTimestamp("ultima_atividade_em")),
                rs.getString("ultima_mensagem_id"),
                rs.getString("ultima_mensagem_previa"),
                local(rs.getTimestamp("ultima_mensagem_em")),
                rs.getLong("nao_lidas"),
                rs.getLong("versao_linha")
        );
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private LocalDateTime local(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

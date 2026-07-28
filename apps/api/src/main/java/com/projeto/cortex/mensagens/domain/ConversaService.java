package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.ConversationCreateRequest;
import com.projeto.cortex.mensagens.api.ConversationResponse;
import com.projeto.cortex.mensagens.api.ParticipantChangeRequest;
import com.projeto.cortex.mensagens.api.ParticipantResponse;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversaService {

    private static final int MAX_TITLE_LENGTH = 200;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final ConversaAccessPolicy accessPolicy;
    private final MessagingOperationalEventService eventService;
    private final ObraOperabilityGuard operabilityGuard;

    public ConversaService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            ConversaAccessPolicy accessPolicy,
            MessagingOperationalEventService eventService,
            ObraOperabilityGuard operabilityGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.accessPolicy = accessPolicy;
        this.eventService = eventService;
        this.operabilityGuard = operabilityGuard;
    }

    @Transactional
    public ConversationResponse create(
            ConversationCreateRequest request,
            MessagingAuditContext audit
    ) {
        if (request == null) {
            throw badRequest("Dados da conversa são obrigatórios.");
        }
        String actorId = currentUserService.requireUserId();
        requireActor(audit, actorId);
        ConversationType type = ConversationType.require(request.tipo());
        String id = optionalUuid(request.id(), UUID.randomUUID().toString());
        String title = normalizeTitle(request.titulo());
        String obraId = optionalUuid(request.obraId(), null);
        String teamId = optionalUuid(request.equipeId(), null);
        Set<String> participants = normalizeParticipants(
                request.participanteIds(),
                actorId
        );

        validateShape(type, title, obraId, teamId, participants);
        if (conversationExists(id)) {
            return get(id);
        }
        validateScope(type, obraId, teamId, actorId);
        for (String participantId : participants) {
            validateParticipantScope(
                    type,
                    obraId,
                    teamId,
                    participantId
            );
        }
        if (obraId != null) {
            operabilityGuard.requireWritable(obraId);
        }

        String directKey = type == ConversationType.DIRETA
                ? directParticipantKey(participants)
                : null;
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO conversa (
                        id, tipo, obra_id, equipe_id, titulo,
                        chave_participantes_direta, criado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    type.name(),
                    obraId,
                    teamId,
                    title,
                    directKey,
                    actorId
            );
        } catch (DuplicateKeyException exception) {
            if (directKey != null) {
                String existingId = jdbcTemplate.query(
                        """
                        SELECT id FROM conversa
                        WHERE chave_participantes_direta = ?
                          AND status = 'ATIVA'
                          AND deletado_em IS NULL
                        LIMIT 1
                        """,
                        rs -> rs.next() ? rs.getString("id") : null,
                        directKey
                );
                if (existingId != null) {
                    return get(existingId);
                }
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe uma conversa com estes dados."
            );
        }

        for (String participantId : participants) {
            insertParticipant(
                    id,
                    participantId,
                    participantId.equals(actorId) ? "ADMIN" : "MEMBRO",
                    actorId
            );
        }

        ConversationScope scope = accessPolicy.requireAccess(id);
        eventService.recordSuccess(
                "CONVERSA",
                id,
                "CONVERSA_CRIADA",
                scope,
                audit,
                Map.of(),
                Map.of(
                        "tipo", type.name(),
                        "participanteIds", List.copyOf(participants)
                )
        );
        return get(id);
    }

    public List<ConversationResponse> list(int requestedLimit) {
        String userId = currentUserService.requireUserId();
        int limit = normalizeLimit(requestedLimit);
        String sql;
        List<Object> arguments = new ArrayList<>();
        if (currentUserService.isAlfa(userId)) {
            sql = """
                    SELECT c.*
                    FROM conversa c
                    WHERE c.status = 'ATIVA' AND c.deletado_em IS NULL
                    ORDER BY c.atualizado_em DESC, c.id
                    LIMIT ?
                    """;
        } else {
            sql = """
                    SELECT DISTINCT c.*
                    FROM conversa c
                    JOIN conversa_participante cp
                      ON cp.conversa_id = c.id
                     AND cp.colaborador_id = ?
                     AND cp.status = 'ATIVO'
                     AND cp.removido_em IS NULL
                     AND cp.deletado_em IS NULL
                    WHERE c.status = 'ATIVA'
                      AND c.deletado_em IS NULL
                      AND (
                        c.tipo IN ('DIRETA', 'GRUPO')
                        OR (c.tipo = 'OBRA' AND EXISTS (
                            SELECT 1 FROM vinculo_colaborador_obra v
                            WHERE v.obra_id = c.obra_id
                              AND v.colaborador_id = ?
                              AND v.status = 'ATIVO'
                        ))
                        OR (c.tipo = 'EQUIPE' AND EXISTS (
                            SELECT 1
                            FROM vinculo_colaborador_obra v
                            JOIN equipe e ON e.id = c.equipe_id
                                         AND e.obra_id = c.obra_id
                                         AND e.status = 'ATIVA'
                                         AND e.deletado_em IS NULL
                            JOIN equipe_membro em ON em.equipe_id = e.id
                            WHERE v.obra_id = c.obra_id
                              AND v.colaborador_id = ?
                              AND v.status = 'ATIVO'
                              AND em.colaborador_id = ?
                              AND em.status = 'ATIVO'
                              AND em.removido_em IS NULL
                              AND em.deletado_em IS NULL
                        ))
                      )
                    ORDER BY c.atualizado_em DESC, c.id
                    LIMIT ?
                    """;
            arguments.add(userId);
            arguments.add(userId);
            arguments.add(userId);
            arguments.add(userId);
        }
        arguments.add(limit);
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapConversation(rs),
                arguments.toArray()
        );
    }

    public ConversationResponse get(String conversationId) {
        ConversationScope scope = accessPolicy.requireAccess(conversationId);
        return jdbcTemplate.query(
                """
                SELECT * FROM conversa WHERE id = ? LIMIT 1
                """,
                rs -> rs.next() ? mapConversation(rs) : null,
                scope.id()
        );
    }

    public List<ParticipantResponse> participants(String conversationId) {
        ConversationScope scope = accessPolicy.requireAccess(conversationId);
        return loadParticipants(scope.id());
    }

    @Transactional
    public ParticipantResponse addParticipant(
            String conversationId,
            ParticipantChangeRequest request,
            MessagingAuditContext audit
    ) {
        ConversationScope scope = accessPolicy.requireAdmin(conversationId);
        String actorId = currentUserService.requireUserId();
        requireActor(audit, actorId);
        if (scope.type() == ConversationType.DIRETA) {
            throw badRequest("Participantes de conversa direta são imutáveis.");
        }
        String participantId = requiredUuid(
                request == null ? null : request.colaboradorId(),
                "colaboradorId"
        );
        String role = normalizeRole(request == null ? null : request.papel());
        validateParticipantScope(
                scope.type(),
                scope.obraId(),
                scope.teamId(),
                participantId
        );
        requireWritable(scope);

        int updated = jdbcTemplate.update(
                """
                UPDATE conversa_participante
                SET papel = ?, status = 'ATIVO', adicionado_em = CURRENT_TIMESTAMP(6),
                    adicionado_por = ?, removido_em = NULL, removido_por = NULL,
                    deletado_em = NULL, versao_linha = versao_linha + 1
                WHERE conversa_id = ? AND colaborador_id = ?
                """,
                role,
                actorId,
                scope.id(),
                participantId
        );
        if (updated == 0) {
            insertParticipant(scope.id(), participantId, role, actorId);
        }
        eventService.recordSuccess(
                "CONVERSA",
                scope.id(),
                "PARTICIPANTE_ADICIONADO",
                scope,
                audit,
                Map.of(),
                Map.of("colaboradorId", participantId, "papel", role)
        );
        return participant(scope.id(), participantId);
    }

    private void requireWritable(ConversationScope scope) {
        if (scope.obraId() != null) {
            operabilityGuard.requireWritable(scope.obraId());
        }
    }

    @Transactional
    public void removeParticipant(
            String conversationId,
            String participantId,
            MessagingAuditContext audit
    ) {
        ConversationScope scope = accessPolicy.requireAdmin(conversationId);
        String actorId = currentUserService.requireUserId();
        requireActor(audit, actorId);
        if (scope.type() == ConversationType.DIRETA) {
            throw badRequest("Participantes de conversa direta são imutáveis.");
        }
        String normalizedId = requiredUuid(participantId, "colaboradorId");
        ParticipantResponse existing = participant(scope.id(), normalizedId);
        if (existing == null || !"ATIVO".equals(existing.status())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Participante ativo não encontrado."
            );
        }
        if ("ADMIN".equals(existing.papel()) && activeAdminCount(scope.id()) <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A conversa precisa manter pelo menos um administrador."
            );
        }
        jdbcTemplate.update(
                """
                UPDATE conversa_participante
                SET status = 'REMOVIDO', removido_em = CURRENT_TIMESTAMP(6),
                    removido_por = ?, deletado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE conversa_id = ? AND colaborador_id = ?
                  AND status = 'ATIVO'
                """,
                actorId,
                scope.id(),
                normalizedId
        );
        eventService.recordSuccess(
                "CONVERSA",
                scope.id(),
                "PARTICIPANTE_REMOVIDO",
                scope,
                audit,
                Map.of("colaboradorId", normalizedId, "papel", existing.papel()),
                Map.of("colaboradorId", normalizedId, "status", "REMOVIDO")
        );
    }

    private ConversationResponse mapConversation(ResultSet rs)
            throws SQLException {
        String id = rs.getString("id");
        return new ConversationResponse(
                id,
                rs.getString("tipo"),
                rs.getString("titulo"),
                rs.getString("obra_id"),
                rs.getString("equipe_id"),
                rs.getString("status"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime(),
                rs.getLong("versao_linha"),
                loadParticipants(id)
        );
    }

    private List<ParticipantResponse> loadParticipants(String conversationId) {
        return jdbcTemplate.query(
                """
                SELECT cp.colaborador_id, c.nome, cp.papel, cp.status,
                       cp.adicionado_em
                FROM conversa_participante cp
                JOIN colaborador c ON c.id = cp.colaborador_id
                WHERE cp.conversa_id = ?
                  AND cp.deletado_em IS NULL
                ORDER BY cp.adicionado_em, cp.colaborador_id
                """,
                (rs, rowNum) -> new ParticipantResponse(
                        rs.getString("colaborador_id"),
                        rs.getString("nome"),
                        rs.getString("papel"),
                        rs.getString("status"),
                        rs.getTimestamp("adicionado_em").toLocalDateTime()
                ),
                conversationId
        );
    }

    private ParticipantResponse participant(
            String conversationId,
            String participantId
    ) {
        return jdbcTemplate.query(
                """
                SELECT cp.colaborador_id, c.nome, cp.papel, cp.status,
                       cp.adicionado_em
                FROM conversa_participante cp
                JOIN colaborador c ON c.id = cp.colaborador_id
                WHERE cp.conversa_id = ? AND cp.colaborador_id = ?
                LIMIT 1
                """,
                rs -> rs.next()
                        ? new ParticipantResponse(
                                rs.getString("colaborador_id"),
                                rs.getString("nome"),
                                rs.getString("papel"),
                                rs.getString("status"),
                                rs.getTimestamp("adicionado_em")
                                        .toLocalDateTime()
                        )
                        : null,
                conversationId,
                participantId
        );
    }

    private void validateShape(
            ConversationType type,
            String title,
            String obraId,
            String teamId,
            Set<String> participants
    ) {
        switch (type) {
            case DIRETA -> {
                if (participants.size() != 2 || title != null
                        || obraId != null || teamId != null) {
                    throw badRequest(
                            "Conversa direta exige exatamente dois participantes e nenhum escopo adicional."
                    );
                }
            }
            case GRUPO -> {
                if (participants.size() < 2 || title == null
                        || obraId != null || teamId != null) {
                    throw badRequest(
                            "Conversa em grupo exige título, ao menos dois participantes e não aceita obra/equipe."
                    );
                }
            }
            case OBRA -> {
                if (obraId == null || teamId != null) {
                    throw badRequest("Conversa de obra exige somente obraId.");
                }
            }
            case EQUIPE -> {
                if (obraId == null || teamId == null) {
                    throw badRequest("Conversa de equipe exige obraId e equipeId.");
                }
            }
        }
    }

    private void validateScope(
            ConversationType type,
            String obraId,
            String teamId,
            String actorId
    ) {
        if (type == ConversationType.DIRETA || type == ConversationType.GRUPO) {
            return;
        }
        if (!currentUserService.isAlfa(actorId)) {
            currentUserService.requireWorksiteAccess(obraId);
        }
        if (type == ConversationType.EQUIPE) {
            Integer exists = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM equipe
                    WHERE id = ? AND obra_id = ? AND status = 'ATIVA'
                      AND deletado_em IS NULL
                    """,
                    Integer.class,
                    teamId,
                    obraId
            );
            if (exists == null || exists != 1) {
                throw badRequest("A equipe não pertence à obra informada.");
            }
        }
    }

    private void validateParticipantScope(
            ConversationType type,
            String obraId,
            String teamId,
            String participantId
    ) {
        Integer active = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM colaborador
                WHERE id = ? AND ativo = TRUE AND deletado_em IS NULL
                """,
                Integer.class,
                participantId
        );
        if (active == null || active != 1) {
            throw badRequest("Participante inexistente ou inativo.");
        }
        if (currentUserService.isAlfa(participantId)
                || type == ConversationType.DIRETA
                || type == ConversationType.GRUPO) {
            return;
        }
        if (!currentUserService.podeAcessarObra(participantId, obraId)) {
            throw badRequest("O participante não possui vínculo ativo com a obra.");
        }
        if (type == ConversationType.EQUIPE) {
            Integer member = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM equipe_membro
                    WHERE equipe_id = ? AND colaborador_id = ?
                      AND status = 'ATIVO' AND removido_em IS NULL
                      AND deletado_em IS NULL
                    """,
                    Integer.class,
                    teamId,
                    participantId
            );
            if (member == null || member != 1) {
                throw badRequest("O participante não integra a equipe ativa.");
            }
        }
    }

    private void insertParticipant(
            String conversationId,
            String participantId,
            String role,
            String actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO conversa_participante (
                    id, conversa_id, colaborador_id, papel, adicionado_por
                ) VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                conversationId,
                participantId,
                role,
                actorId
        );
    }

    private boolean conversationExists(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversa WHERE id = ?",
                Integer.class,
                id
        );
        return count != null && count > 0;
    }

    private int activeAdminCount(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM conversa_participante
                WHERE conversa_id = ? AND papel = 'ADMIN'
                  AND status = 'ATIVO' AND deletado_em IS NULL
                """,
                Integer.class,
                conversationId
        );
        return count == null ? 0 : count;
    }

    private Set<String> normalizeParticipants(
            List<String> participantIds,
            String actorId
    ) {
        Set<String> result = new LinkedHashSet<>();
        result.add(actorId);
        if (participantIds != null) {
            participantIds.forEach(id -> result.add(requiredUuid(
                    id,
                    "participanteIds"
            )));
        }
        return result;
    }

    private String directParticipantKey(Set<String> participants) {
        String material = participants.stream()
                .sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    private String normalizeTitle(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw badRequest("Título da conversa excede 200 caracteres.");
        }
        return normalized;
    }

    private String normalizeRole(String value) {
        if (value == null || value.isBlank()) {
            return "MEMBRO";
        }
        String normalized = value.strip().toUpperCase();
        if (!Set.of("ADMIN", "MEMBRO").contains(normalized)) {
            throw badRequest("Papel de participante inválido.");
        }
        return normalized;
    }

    private String optionalUuid(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : requiredUuid(value, "id");
    }

    private String requiredUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        try {
            return UUID.fromString(value.strip()).toString();
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " deve ser um UUID válido.");
        }
    }

    private int normalizeLimit(int value) {
        if (value <= 0) {
            return 50;
        }
        return Math.min(value, 100);
    }

    private void requireActor(MessagingAuditContext audit, String actorId) {
        if (audit == null || audit.actorId() == null
                || !actorId.equals(audit.actorId())) {
            throw new IllegalArgumentException(
                    "Contexto de auditoria não corresponde ao usuário autenticado."
            );
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

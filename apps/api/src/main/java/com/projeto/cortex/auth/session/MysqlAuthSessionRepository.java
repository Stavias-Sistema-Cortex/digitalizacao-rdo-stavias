package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.PapelAcesso;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Legacy MySQL persistence adapter for opaque authentication sessions. */
@Repository
@Profile("!postgresql-common")
public class MysqlAuthSessionRepository implements AuthSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public MysqlAuthSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Instant create(String sessionId, String collaboratorId,
            String tokenHash, String csrfHash, String clientInstanceHash,
            int ttlSeconds) {
        validateCreate(sessionId, collaboratorId, tokenHash, csrfHash,
                clientInstanceHash, ttlSeconds);
        int inserted = jdbcTemplate.update("""
                INSERT INTO auth_session (
                    id, colaborador_id, token_hash, csrf_hash,
                    client_instance_hash, client_instance_bound, expira_em
                ) VALUES (?, ?, ?, ?, ?, TRUE,
                    CURRENT_TIMESTAMP(6) + (? * INTERVAL '1 second'))
                """, sessionId, collaboratorId, tokenHash, csrfHash,
                clientInstanceHash, ttlSeconds);
        if (inserted != 1) {
            throw new IllegalStateException("Sessão não persistida.");
        }
        Timestamp expiresAt = jdbcTemplate.queryForObject("""
                SELECT expira_em FROM auth_session WHERE id = ?
                """, Timestamp.class, sessionId);
        if (expiresAt == null) {
            throw new IllegalStateException("Expiração da sessão indisponível.");
        }
        return expiresAt.toInstant();
    }

    @Override
    public Optional<ResolvedAuthSession> findActiveByTokenHashAndClientInstanceHash(
            String tokenHash,
            String clientInstanceHash
    ) {
        requireHash(tokenHash, "token");
        requireHash(clientInstanceHash, "instância do cliente");
        List<ResolvedAuthSession> sessions = jdbcTemplate.query("""
                SELECT session.id, session.colaborador_id, session.csrf_hash,
                    session.client_instance_hash,
                    session.expira_em, colaborador.nome, colaborador.papel_acesso
                FROM auth_session session
                INNER JOIN colaborador ON colaborador.id = session.colaborador_id
                WHERE session.token_hash = ?
                    AND session.client_instance_hash = ?
                    AND session.revogado_em IS NULL
                    AND session.expira_em > CURRENT_TIMESTAMP(6)
                    AND session.client_instance_bound = TRUE
                    AND session.client_instance_hash IS NOT NULL
                    AND colaborador.ativo = TRUE
                    AND colaborador.deletado_em IS NULL
                    AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                """, (resultSet, rowNumber) -> resolved(resultSet), tokenHash,
                clientInstanceHash);
        if (sessions.size() > 1) {
            throw new IllegalStateException("Token de sessão ambíguo.");
        }
        return sessions.stream().findFirst();
    }

    @Override
    public int revokeByTokenHash(String tokenHash, String reason) {
        requireHash(tokenHash, "token");
        return jdbcTemplate.update("""
                UPDATE auth_session
                SET revogado_em = CURRENT_TIMESTAMP(6), revogado_motivo = ?
                WHERE token_hash = ? AND revogado_em IS NULL
                """, requireReason(reason), tokenHash);
    }

    static ResolvedAuthSession resolved(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ResolvedAuthSession(resultSet.getString("id"),
                resultSet.getString("colaborador_id"), resultSet.getString("nome"),
                PapelAcesso.fromPersistedExact(resultSet.getString("papel_acesso"))
                        .orElseThrow(() -> new IllegalStateException("Papel persistido inválido.")),
                resultSet.getTimestamp("expira_em").toInstant(),
                resultSet.getString("csrf_hash"),
                resultSet.getString("client_instance_hash"));
    }

    static void validateCreate(String sessionId, String collaboratorId,
            String tokenHash, String csrfHash, String clientInstanceHash,
            int ttlSeconds) {
        requireUuid(sessionId, "sessão");
        requireUuid(collaboratorId, "colaborador");
        requireHash(tokenHash, "token");
        requireHash(csrfHash, "CSRF");
        requireHash(clientInstanceHash, "instância do cliente");
        if (ttlSeconds < 1) {
            throw invalidInput();
        }
    }

    static void requireUuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw invalidInput();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Identificador de " + field + " inválido.");
        }
    }

    static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Hash de " + field + " inválido.");
        }
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Motivo da revogação obrigatório.");
        }
        String normalized = reason.strip();
        if (normalized.length() > 120 || normalized.contains("\r") || normalized.contains("\n")) {
            throw new IllegalArgumentException("Motivo da revogação inválido.");
        }
        return normalized;
    }

    static IllegalArgumentException invalidInput() {
        return new IllegalArgumentException("Sessão inválida.");
    }
}

package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.PapelAcesso;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence boundary for opaque, revocable authentication sessions. */
@Repository
public class JdbcAuthSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Instant create(
            String sessionId,
            String collaboratorId,
            String tokenHash,
            String csrfHash,
            int ttlSeconds
    ) {
        requireUuid(sessionId, "sessão");
        requireUuid(collaboratorId, "colaborador");
        requireHash(tokenHash, "token");
        requireHash(csrfHash, "CSRF");
        if (ttlSeconds < 1) {
            throw invalidInput();
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO auth_session (
                    id,
                    colaborador_id,
                    token_hash,
                    csrf_hash,
                    expira_em
                ) VALUES (
                    ?, ?, ?, ?,
                    TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))
                )
                """,
                sessionId,
                collaboratorId,
                tokenHash,
                csrfHash,
                ttlSeconds
        );
        if (inserted != 1) {
            throw new IllegalStateException("Sessão não persistida.");
        }
        Timestamp expiresAt = jdbcTemplate.queryForObject("""
                SELECT expira_em
                FROM auth_session
                WHERE id = ?
                """, Timestamp.class, sessionId);
        if (expiresAt == null) {
            throw new IllegalStateException(
                    "Expiração da sessão indisponível."
            );
        }
        return expiresAt.toInstant();
    }

    public Optional<ResolvedAuthSession> findActiveByTokenHash(
            String tokenHash
    ) {
        requireHash(tokenHash, "token");
        List<ResolvedAuthSession> sessions = jdbcTemplate.query("""
                SELECT
                    session.id,
                    session.colaborador_id,
                    session.csrf_hash,
                    session.expira_em,
                    colaborador.nome,
                    colaborador.papel_acesso
                FROM auth_session session
                INNER JOIN colaborador
                    ON colaborador.id = session.colaborador_id
                WHERE session.token_hash = ?
                  AND session.revogado_em IS NULL
                  AND session.expira_em > CURRENT_TIMESTAMP(6)
                  AND colaborador.ativo = 1
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                """,
                (resultSet, rowNumber) -> new ResolvedAuthSession(
                        resultSet.getString("id"),
                        resultSet.getString("colaborador_id"),
                        resultSet.getString("nome"),
                        PapelAcesso.fromPersistedExact(
                                resultSet.getString("papel_acesso")
                        ).orElseThrow(() -> new IllegalStateException(
                                "Papel persistido inválido."
                        )),
                        resultSet.getTimestamp("expira_em").toInstant(),
                        resultSet.getString("csrf_hash")
                ),
                tokenHash
        );
        if (sessions.size() > 1) {
            throw new IllegalStateException("Token de sessão ambíguo.");
        }
        return sessions.stream().findFirst();
    }

    public int revokeByTokenHash(String tokenHash, String reason) {
        requireHash(tokenHash, "token");
        String normalizedReason = requireReason(reason);
        return jdbcTemplate.update("""
                UPDATE auth_session
                SET revogado_em = CURRENT_TIMESTAMP(6),
                    revogado_motivo = ?
                WHERE token_hash = ?
                  AND revogado_em IS NULL
                """,
                normalizedReason,
                tokenHash
        );
    }

    private void requireUuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw invalidInput();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Identificador de " + field + " inválido."
            );
        }
    }

    private void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Hash de " + field + " inválido."
            );
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Motivo da revogação obrigatório."
            );
        }
        String normalized = reason.strip();
        if (normalized.length() > 120
                || normalized.contains("\r")
                || normalized.contains("\n")) {
            throw new IllegalArgumentException(
                    "Motivo da revogação inválido."
            );
        }
        return normalized;
    }

    private IllegalArgumentException invalidInput() {
        return new IllegalArgumentException("Sessão inválida.");
    }
}

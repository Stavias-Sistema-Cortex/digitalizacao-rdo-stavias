package com.projeto.cortex.auth.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL native repository for opaque, revocable authentication sessions. */
@Repository
@Profile("postgresql-common")
public class PostgresqlAuthSessionRepository implements AuthSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlAuthSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Instant create(String sessionId, String collaboratorId,
            String tokenHash, String csrfHash, String clientInstanceHash,
            int ttlSeconds) {
        MysqlAuthSessionRepository.validateCreate(
                sessionId, collaboratorId, tokenHash, csrfHash,
                clientInstanceHash, ttlSeconds);
        Timestamp expiresAt = jdbcTemplate.queryForObject("""
                INSERT INTO auth_session (
                    id, colaborador_id, token_hash, csrf_hash,
                    client_instance_hash, client_instance_bound, expira_em
                ) VALUES (?, ?, ?, ?, ?, TRUE,
                    clock_timestamp() + (? * INTERVAL '1 second'))
                RETURNING expira_em
                """, Timestamp.class, sessionId, collaboratorId, tokenHash,
                csrfHash, clientInstanceHash, ttlSeconds);
        if (expiresAt == null) {
            throw new IllegalStateException("Sessão não persistida.");
        }
        return expiresAt.toInstant();
    }

    @Override
    public Optional<ResolvedAuthSession> findActiveByTokenHashAndClientInstanceHash(
            String tokenHash,
            String clientInstanceHash
    ) {
        MysqlAuthSessionRepository.requireHash(tokenHash, "token");
        MysqlAuthSessionRepository.requireHash(
                clientInstanceHash,
                "instância do cliente"
        );
        List<ResolvedAuthSession> sessions = jdbcTemplate.query("""
                SELECT session.id, session.colaborador_id, session.csrf_hash,
                    session.client_instance_hash,
                    session.expira_em, colaborador.nome, colaborador.papel_acesso
                FROM auth_session session
                INNER JOIN colaborador ON colaborador.id = session.colaborador_id
                INNER JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE session.token_hash = ?
                    AND session.client_instance_hash = ?
                    AND session.revogado_em IS NULL
                    AND session.expira_em > clock_timestamp()
                    AND session.client_instance_bound = TRUE
                    AND session.client_instance_hash IS NOT NULL
                    AND colaborador.ativo = TRUE
                    AND colaborador.deletado_em IS NULL
                    AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                    AND identity.status = 'ATIVA'
                """, (resultSet, rowNumber) -> MysqlAuthSessionRepository.resolved(resultSet), tokenHash,
                clientInstanceHash);
        if (sessions.size() > 1) {
            throw new IllegalStateException("Token de sessão ambíguo.");
        }
        return sessions.stream().findFirst();
    }

    @Override
    public int revokeByTokenHash(String tokenHash, String reason) {
        MysqlAuthSessionRepository.requireHash(tokenHash, "token");
        return jdbcTemplate.update("""
                UPDATE auth_session
                SET revogado_em = clock_timestamp(), revogado_motivo = ?
                WHERE token_hash = ? AND revogado_em IS NULL
                """, MysqlAuthSessionRepository.requireReason(reason), tokenHash);
    }
}

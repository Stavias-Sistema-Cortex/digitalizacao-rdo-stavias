package com.projeto.cortex.auth.postgresql;

import com.projeto.cortex.auth.session.PostgresqlAuthSessionRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlAuthSessionRepositoryIT
        extends PostgresqlAuthPersistenceTestSupport {

    @Test
    void resolvesOnlyAnUnrevokedSessionForAnActiveAtivaIdentity() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String collaboratorId = "00000000-0000-4000-8000-000000000401";
            String sessionId = "00000000-0000-4000-8000-000000000402";
            String tokenHash = digest('e');
            insertIdentity(jdbc, collaboratorId,
                    "operator.session@fixture.invalid", "ATIVA", true);
            PostgresqlAuthSessionRepository sessions =
                    new PostgresqlAuthSessionRepository(jdbc);

            Instant expiry = sessions.create(sessionId, collaboratorId,
                    tokenHash, digest('f'), 300);
            assertThat(expiry).isAfter(Instant.now().minusSeconds(2));
            assertThat(sessions.findActiveByTokenHash(tokenHash)).isPresent();

            jdbc.update("""
                    UPDATE auth_identity SET status = 'BLOQUEADA'
                    WHERE colaborador_id = ?
                    """, collaboratorId);
            assertThat(sessions.findActiveByTokenHash(tokenHash)).isEmpty();

            jdbc.update("""
                    UPDATE auth_identity SET status = 'ATIVA'
                    WHERE colaborador_id = ?
                    """, collaboratorId);
            assertThat(sessions.revokeByTokenHash(tokenHash, "TEST_LOGOUT"))
                    .isEqualTo(1);
            assertThat(sessions.findActiveByTokenHash(tokenHash)).isEmpty();
        }
    }

    @Test
    void doesNotResolveAnOpaqueSessionForAnAtivaIdentityWithoutAuthenticationEmail() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String collaboratorId = "00000000-0000-4000-8000-000000000411";
            String sessionId = "00000000-0000-4000-8000-000000000412";
            String tokenHash = digest('a');
            insertIdentity(jdbc, collaboratorId,
                    "operator.null-email@fixture.invalid", "ATIVA", true);
            jdbc.update("""
                    UPDATE auth_identity
                    SET email_autenticacao = NULL
                    WHERE colaborador_id = ?
                    """, collaboratorId);

            PostgresqlAuthSessionRepository sessions =
                    new PostgresqlAuthSessionRepository(jdbc);
            sessions.create(sessionId, collaboratorId, tokenHash, digest('b'), 300);

            assertThat(sessions.findActiveByTokenHash(tokenHash)).isEmpty();
        }
    }
}

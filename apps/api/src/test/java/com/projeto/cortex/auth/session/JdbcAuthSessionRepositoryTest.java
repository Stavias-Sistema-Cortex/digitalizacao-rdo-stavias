package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAuthSessionRepositoryTest {

    @Test
    void createsExpiryFromDatabaseClockAndReturnsThatExactValue() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcAuthSessionRepository repository =
                new JdbcAuthSessionRepository(jdbc);
        Instant databaseExpiry = Instant.parse("2030-01-02T03:04:05Z");
        when(jdbc.update(
                anyString(),
                eq("20000000-0000-0000-0000-000000000002"),
                eq("10000000-0000-0000-0000-000000000001"),
                eq("a".repeat(64)),
                eq("b".repeat(64)),
                eq(43_200)
        )).thenReturn(1);
        when(jdbc.queryForObject(
                anyString(),
                eq(Timestamp.class),
                eq("20000000-0000-0000-0000-000000000002")
        )).thenReturn(Timestamp.from(databaseExpiry));

        Instant result = repository.create(
                "20000000-0000-0000-0000-000000000002",
                "10000000-0000-0000-0000-000000000001",
                "a".repeat(64),
                "b".repeat(64),
                43_200
        );

        assertThat(result).isEqualTo(databaseExpiry);
        ArgumentCaptor<String> insertSql =
                ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                insertSql.capture(),
                eq("20000000-0000-0000-0000-000000000002"),
                eq("10000000-0000-0000-0000-000000000001"),
                eq("a".repeat(64)),
                eq("b".repeat(64)),
                eq(43_200)
        );
        assertThat(compact(insertSql.getValue()))
                .contains("CURRENT_TIMESTAMP(6) + (? * INTERVAL '1 second')")
                .doesNotContain("raw");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesOnlyLiveSessionsForLiveCollaboratorsWithCanonicalRoles() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcAuthSessionRepository repository =
                new JdbcAuthSessionRepository(jdbc);
        ResolvedAuthSession expected = SessionTokenFixtures.resolved(
                SessionTokenFixtures.token((byte) 4)
        );
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq("a".repeat(64))
        )).thenReturn(List.of(expected));

        assertThat(repository.findActiveByTokenHash("a".repeat(64)))
                .containsSame(expected);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                any(RowMapper.class),
                eq("a".repeat(64))
        );
        assertThat(compact(sql.getValue()))
                .contains("JOIN colaborador")
                .contains("session.revogado_em IS NULL")
                .contains("session.expira_em > CURRENT_TIMESTAMP(6)")
                .contains("colaborador.ativo = TRUE")
                .contains("colaborador.deletado_em IS NULL")
                .contains("colaborador.papel_acesso IN ('ALFA', 'BETA')");
    }

    @Test
    void revocationIsGuardedAndThereforeIdempotent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcAuthSessionRepository repository =
                new JdbcAuthSessionRepository(jdbc);
        when(jdbc.update(
                anyString(),
                eq("LOGOUT"),
                eq("a".repeat(64))
        )).thenReturn(1);

        assertThat(repository.revokeByTokenHash(
                "a".repeat(64),
                "LOGOUT"
        )).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                sql.capture(),
                eq("LOGOUT"),
                eq("a".repeat(64))
        );
        assertThat(compact(sql.getValue()))
                .contains("revogado_em = CURRENT_TIMESTAMP(6)")
                .contains("WHERE token_hash = ?")
                .contains("revogado_em IS NULL");
    }

    private String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}

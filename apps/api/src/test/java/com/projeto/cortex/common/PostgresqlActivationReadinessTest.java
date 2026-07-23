package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresqlActivationReadinessTest {

    @Test
    void reportsPendingWhenV54AndDatabaseAreAvailableWithoutAnActiveAlfa() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(
                invocation -> 1
        );
        PostgresqlActivationReadiness readiness =
                new PostgresqlActivationReadiness(jdbc);

        readiness.verifyRuntimeReadiness();

        assertThat(readiness.readinessStatus()).isEqualTo("ATIVACAO_PENDENTE");
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.argThat(
                        sql -> sql.contains("version = '54'")
                ),
                eq(Integer.class)
        );
    }

    @Test
    void rejectsMissingOrMalformedV54WithoutCheckingForAnAlfa() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(
                invocation -> "SELECT 1".equals(invocation.getArgument(0))
                        ? 1 : 0
        );
        PostgresqlActivationReadiness readiness =
                new PostgresqlActivationReadiness(jdbc);

        assertThatThrownBy(readiness::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V54");
    }
}

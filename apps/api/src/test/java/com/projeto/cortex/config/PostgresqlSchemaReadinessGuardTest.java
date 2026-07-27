package com.projeto.cortex.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlSchemaReadinessGuardTest {

    @Test
    void configuredGuardRequiresTheCompleteV61Chain() throws Exception {
        var field = PostgresqlSchemaReadinessGuard.class.getDeclaredField(
                "CLEAN_START_REQUIRED_SCHEMA_VERSION"
        );
        field.setAccessible(true);

        assertThat(field.get(null)).isEqualTo("61");
    }

    @Test
    void isScopedToPostgresqlCommonAndAnExplicitSchemaGate() {
        Profile profile = PostgresqlSchemaReadinessGuard.class.getAnnotation(Profile.class);
        ConditionalOnProperty conditional = PostgresqlSchemaReadinessGuard.class
                .getAnnotation(ConditionalOnProperty.class);

        org.assertj.core.api.Assertions.assertThat(profile.value())
                .containsExactly("postgresql-common");
        org.assertj.core.api.Assertions.assertThat(conditional.name())
                .containsExactly("cortex.postgresql.schema-readiness.enabled");
        org.assertj.core.api.Assertions.assertThat(conditional.havingValue()).isEqualTo("true");
    }

    @Test
    void executesAsAnEarlyBeanFactoryPreflight() {
        org.assertj.core.api.Assertions.assertThat(BeanFactoryPostProcessor.class)
                .isAssignableFrom(PostgresqlSchemaReadinessGuard.class);
    }

    @Test
    void refusesAnEmptyDatabaseWhenFlywayHistoryIsUnavailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("flyway_schema_history ausente"));

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbcTemplate, "61"
        );

        assertThatThrownBy(guard::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadeia de migrações até V61")
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void refusesWhenTheExplicitV61RowIsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbcTemplate, "61"
        );

        assertThatThrownBy(guard::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadeia de migrações até V61");
        verify(jdbcTemplate).queryForObject(contains("version = '61'"), eq(Integer.class));
    }

    @Test
    void acceptsACompletedV61MigrationChain() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbcTemplate, "61"
        );

        assertThatCode(guard::verifyReadiness).doesNotThrowAnyException();
    }
}

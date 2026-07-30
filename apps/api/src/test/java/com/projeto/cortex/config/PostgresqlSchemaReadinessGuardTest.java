package com.projeto.cortex.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

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
    void configuredGuardRequiresTheCompleteV64Chain() throws Exception {
        assertThat(PostgresqlSchemaVersion.REQUIRED).isEqualTo("64");
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
    void configuredPreflightUsesTheJvmTrustStoreForVerifyFullTls() {
        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard();
        guard.setEnvironment(new MockEnvironment()
                .withProperty(
                        "spring.datasource.url",
                        "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/"
                                + "Sta" + "vias"
                                + "Cortex?sslmode=verify-full&channelBinding=require"
                )
                .withProperty("spring.datasource.username", "cortex_contract")
                .withProperty("spring.datasource.password", "contract-password"));

        JdbcTemplate jdbcTemplate = ReflectionTestUtils.invokeMethod(
                guard,
                "postgresqlJdbcTemplate"
        );
        DriverManagerDataSource dataSource = (DriverManagerDataSource)
                jdbcTemplate.getDataSource();

        assertThat(dataSource.getConnectionProperties())
                .containsEntry(
                        "sslfactory",
                        "org.postgresql.ssl.DefaultJavaSSLFactory"
                );
    }

    @Test
    void refusesAnEmptyDatabaseWhenFlywayHistoryIsUnavailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("flyway_schema_history ausente"));

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbcTemplate, "64"
        );

        assertThatThrownBy(guard::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadeia de migrações até V64")
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void refusesWhenTheExplicitV64RowIsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbcTemplate, "64"
        );

        assertThatThrownBy(guard::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadeia de migrações até V64");
        verify(jdbcTemplate).queryForObject(
                contains("FROM public.flyway_schema_history"),
                eq(Integer.class)
        );
    }

    @Test
    void acceptsACompletedV64MigrationChain() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbcTemplate, "64"
        );

        assertThatCode(guard::verifyReadiness).doesNotThrowAnyException();
    }
}

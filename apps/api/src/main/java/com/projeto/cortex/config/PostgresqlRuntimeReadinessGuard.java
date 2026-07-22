package com.projeto.cortex.config;

import com.projeto.cortex.common.RuntimeReadiness;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/** Refuses normal PostgreSQL runtime until every clean-start release gate is true. */
@Component
@Profile("postgresql")
public final class PostgresqlRuntimeReadinessGuard implements
        BeanFactoryPostProcessor,
        EnvironmentAware,
        PriorityOrdered,
        RuntimeReadiness {

    private static final String CLEAN_START_REQUIRED_SCHEMA_VERSION = "52";

    private static final String COMPLETED_REQUIRED_VERSION_SQL = """
            SELECT COUNT(*)
            FROM flyway_schema_history
            WHERE version = '%s'
              AND success = TRUE
            """;

    private static final String VERIFIED_ACTIVE_ALFA_COUNT_SQL = """
            SELECT COUNT(*)
            FROM colaborador c
            JOIN auth_identity ai ON ai.colaborador_id = c.id
            WHERE c.ativo = TRUE
              AND c.deletado_em IS NULL
              AND c.papel_acesso = 'ALFA'
              AND ai.status = 'ATIVA'
              AND ai.email_verificado_em IS NOT NULL
            """;

    private final JdbcTemplate testJdbcTemplate;
    private final String testRequiredSchemaVersion;
    private final Boolean testRuntimeReady;
    private final PostgresqlRuntimeSurfaceRegistry testSurfaceRegistry;
    private Environment environment;

    public PostgresqlRuntimeReadinessGuard() {
        this.testJdbcTemplate = null;
        this.testRequiredSchemaVersion = null;
        this.testRuntimeReady = null;
        this.testSurfaceRegistry = null;
    }

    PostgresqlRuntimeReadinessGuard(
            JdbcTemplate jdbcTemplate,
            String requiredSchemaVersion,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry surfaceRegistry
    ) {
        this.testJdbcTemplate = jdbcTemplate;
        this.testRequiredSchemaVersion = requiredSchemaVersion;
        this.testRuntimeReady = runtimeReady;
        this.testSurfaceRegistry = surfaceRegistry;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        verifyConfiguredReadiness();
    }

    private void verifyConfiguredReadiness() {
        verifyReadiness(
                postgresqlJdbcTemplate(),
                CLEAN_START_REQUIRED_SCHEMA_VERSION,
                environment.getProperty("cortex.postgresql.runtime-ready", Boolean.class, false),
                new PostgresqlRuntimeSurfaceRegistry()
        );
    }

    void verifyReadiness() {
        if (testJdbcTemplate == null
                || testRequiredSchemaVersion == null
                || testRuntimeReady == null
                || testSurfaceRegistry == null) {
            throw new IllegalStateException(
                    "A verificação de runtime PostgreSQL exige o perfil postgresql."
            );
        }
        verifyReadiness(
                testJdbcTemplate,
                testRequiredSchemaVersion,
                testRuntimeReady,
                testSurfaceRegistry
        );
    }

    @Override
    public void verifyRuntimeReadiness() {
        JdbcTemplate jdbcTemplate = effectiveJdbcTemplate();
        Boolean databaseReady = jdbcTemplate.queryForObject("SELECT TRUE", Boolean.class);
        if (!Boolean.TRUE.equals(databaseReady)) {
            throw new IllegalStateException("PostgreSQL indisponível para readiness.");
        }

        if (testJdbcTemplate != null) {
            verifyReadiness();
            return;
        }
        verifyConfiguredReadiness();
    }

    private JdbcTemplate effectiveJdbcTemplate() {
        return testJdbcTemplate == null ? postgresqlJdbcTemplate() : testJdbcTemplate;
    }

    private JdbcTemplate postgresqlJdbcTemplate() {
        if (environment == null) {
            throw new IllegalStateException("Ambiente PostgreSQL indisponível para a verificação.");
        }
        return new JdbcTemplate(new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username", ""),
                environment.getProperty("spring.datasource.password", "")
        ));
    }

    private static void verifyReadiness(
            JdbcTemplate jdbcTemplate,
            String requiredSchemaVersion,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry surfaceRegistry
    ) {
        if (!runtimeReady) {
            throw new IllegalStateException(
                    "Runtime PostgreSQL bloqueado: defina CORTEX_POSTGRES_RUNTIME_READY=true "
                            + "somente após a liberação operacional."
            );
        }
        if (!surfaceRegistry.hasCompleteRuntimeSurfaceSet()) {
            throw new IllegalStateException(
                    "Runtime PostgreSQL bloqueado: o conjunto completo e exato de superfícies "
                            + "operacionais PostgreSQL não foi liberado."
            );
        }

        Integer completedRequiredVersion;
        try {
            completedRequiredVersion = jdbcTemplate.queryForObject(
                    COMPLETED_REQUIRED_VERSION_SQL.formatted(requiredSchemaVersion),
                    Integer.class
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "PostgreSQL Córtex não está pronto para o runtime normal.",
                    exception
            );
        }

        if (completedRequiredVersion == null || completedRequiredVersion < 1) {
            throw new IllegalStateException(
                    "Runtime PostgreSQL exige a cadeia de migrações até V"
                            + requiredSchemaVersion + "."
            );
        }

        Integer verifiedActiveAlfas;
        try {
            verifiedActiveAlfas = jdbcTemplate.queryForObject(
                    VERIFIED_ACTIVE_ALFA_COUNT_SQL,
                    Integer.class
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "PostgreSQL Córtex não está pronto para validar o ALFA inicial.",
                    exception
            );
        }
        if (verifiedActiveAlfas == null || verifiedActiveAlfas < 1) {
            throw new IllegalStateException(
                    "Runtime PostgreSQL exige ao menos um ALFA ativo, autenticável e com e-mail verificado."
            );
        }
    }
}

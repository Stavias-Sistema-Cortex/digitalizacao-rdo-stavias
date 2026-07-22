package com.projeto.cortex.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Refuses the PostgreSQL profile before the web server is initialized unless a
 * tested Córtex migration chain has been installed. The current MySQL schema cannot
 * be replayed against PostgreSQL because it contains vendor-specific
 * migrations and SQL.
 */
@Component
@Profile("postgresql-common")
@ConditionalOnProperty(
        name = "cortex.postgresql.schema-readiness.enabled",
        havingValue = "true"
)
public final class PostgresqlSchemaReadinessGuard
        implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final String CLEAN_START_REQUIRED_SCHEMA_VERSION = "51";

    private static final String COMPLETED_REQUIRED_VERSION_SQL = """
            SELECT COUNT(*)
            FROM flyway_schema_history
            WHERE version = '%s'
              AND success = TRUE
            """;

    private final JdbcTemplate testJdbcTemplate;
    private final String testRequiredSchemaVersion;
    private Environment environment;

    public PostgresqlSchemaReadinessGuard() {
        this.testJdbcTemplate = null;
        this.testRequiredSchemaVersion = null;
    }

    PostgresqlSchemaReadinessGuard(JdbcTemplate jdbcTemplate, String requiredSchemaVersion) {
        this.testJdbcTemplate = jdbcTemplate;
        this.testRequiredSchemaVersion = requiredSchemaVersion;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        verifyReadiness(
                postgresqlJdbcTemplate(),
                CLEAN_START_REQUIRED_SCHEMA_VERSION
        );
    }

    void verifyReadiness() {
        if (testJdbcTemplate == null || testRequiredSchemaVersion == null) {
            throw new IllegalStateException(
                    "A verificação PostgreSQL só pode ser executada pelo perfil postgresql."
            );
        }
        verifyReadiness(testJdbcTemplate, testRequiredSchemaVersion);
    }

    private JdbcTemplate postgresqlJdbcTemplate() {
        if (environment == null) {
            throw new IllegalStateException("Ambiente PostgreSQL indisponível para a verificação.");
        }

        String url = environment.getRequiredProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");

        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    private static void verifyReadiness(JdbcTemplate jdbcTemplate, String requiredSchemaVersion) {
        Integer completedRequiredVersion;
        try {
            completedRequiredVersion = jdbcTemplate.queryForObject(
                    COMPLETED_REQUIRED_VERSION_SQL.formatted(requiredSchemaVersion),
                    Integer.class
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "PostgreSQL Córtex não está pronto: a cadeia de migrações até V"
                            + requiredSchemaVersion
                            + " não foi encontrada.",
                exception
            );
        }

        if (completedRequiredVersion == null || completedRequiredVersion < 1) {
            throw new IllegalStateException(
                    "PostgreSQL Córtex não está pronto: a cadeia de migrações até V"
                            + requiredSchemaVersion
                            + " é obrigatória e deve constar como concluída."
            );
        }
    }
}

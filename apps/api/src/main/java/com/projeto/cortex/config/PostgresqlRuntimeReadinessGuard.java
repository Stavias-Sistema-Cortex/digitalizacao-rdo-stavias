package com.projeto.cortex.config;

import com.projeto.cortex.common.RuntimeReadiness;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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

    static final long DEFAULT_ACADEMY_READINESS_MAX_AGE_MS = 900_000L;
    private static final String ACADEMY_CONNECTOR_NAME = "acad_colaborador_import";

    private static final String COMPLETED_REQUIRED_VERSION_SQL = """
            SELECT COUNT(*)
            FROM flyway_schema_history
            WHERE version = '%s'
              AND success = TRUE
            """;

    private static final String ACTIVE_ACADEMY_CPF_IDENTITY_COUNT_SQL = """
            SELECT COUNT(*)
            FROM colaborador c
            JOIN auth_identity ai ON ai.colaborador_id = c.id
            WHERE c.ativo = TRUE
              AND c.deletado_em IS NULL
              AND c.banco_origem = 'dbstavias_acad'
              AND c.tabela_origem = 'usuarios'
              AND ai.status = 'ATIVA'
              AND ai.cpf_lookup_key_id IS NOT NULL
              AND ai.cpf_lookup_hmac IS NOT NULL
            """;

    private static final String LATEST_ACADEMY_SYNC_RUN_SQL = """
            SELECT
                status,
                finished_at,
                LOCALTIMESTAMP AS database_now
            FROM source_sync_run
            WHERE connector_name = ?
            ORDER BY started_at DESC, id DESC
            LIMIT 1
            """;

    private final JdbcTemplate testJdbcTemplate;
    private final String testRequiredSchemaVersion;
    private final Boolean testRuntimeReady;
    private final PostgresqlRuntimeSurfaceRegistry testSurfaceRegistry;
    private final Boolean testAcademySyncEnabled;
    private final Long testAcademyReadinessMaxAgeMs;
    private Environment environment;

    public PostgresqlRuntimeReadinessGuard() {
        this.testJdbcTemplate = null;
        this.testRequiredSchemaVersion = null;
        this.testRuntimeReady = null;
        this.testSurfaceRegistry = null;
        this.testAcademySyncEnabled = null;
        this.testAcademyReadinessMaxAgeMs = null;
    }

    PostgresqlRuntimeReadinessGuard(
            JdbcTemplate jdbcTemplate,
            String requiredSchemaVersion,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry surfaceRegistry
    ) {
        this(
                jdbcTemplate,
                requiredSchemaVersion,
                runtimeReady,
                surfaceRegistry,
                false,
                DEFAULT_ACADEMY_READINESS_MAX_AGE_MS
        );
    }

    PostgresqlRuntimeReadinessGuard(
            JdbcTemplate jdbcTemplate,
            String requiredSchemaVersion,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry surfaceRegistry,
            boolean academySyncEnabled,
            long academyReadinessMaxAgeMs
    ) {
        this.testJdbcTemplate = jdbcTemplate;
        this.testRequiredSchemaVersion = requiredSchemaVersion;
        this.testRuntimeReady = runtimeReady;
        this.testSurfaceRegistry = surfaceRegistry;
        this.testAcademySyncEnabled = academySyncEnabled;
        this.testAcademyReadinessMaxAgeMs = academyReadinessMaxAgeMs;
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
        verifyConfiguredStartupReadiness();
    }

    private void verifyConfiguredStartupReadiness() {
        verifyReadiness(
                postgresqlJdbcTemplate(),
                PostgresqlSchemaVersion.REQUIRED,
                environment.getProperty("cortex.postgresql.runtime-ready", Boolean.class, false),
                new PostgresqlRuntimeSurfaceRegistry()
        );
    }

    private void verifyConfiguredRuntimeReadiness() {
        JdbcTemplate jdbcTemplate = postgresqlJdbcTemplate();
        verifyReadiness(
                jdbcTemplate,
                PostgresqlSchemaVersion.REQUIRED,
                environment.getProperty("cortex.postgresql.runtime-ready", Boolean.class, false),
                new PostgresqlRuntimeSurfaceRegistry()
        );
        verifyAcademySyncReadiness(
                jdbcTemplate,
                environment.getProperty("cortex.sync.academy.enabled", Boolean.class, false),
                environment.getProperty(
                        "cortex.sync.academy.readiness-max-age-ms",
                        Long.class,
                        DEFAULT_ACADEMY_READINESS_MAX_AGE_MS
                )
        );
    }

    void verifyReadiness() {
        if (testJdbcTemplate == null
                || testRequiredSchemaVersion == null
                || testRuntimeReady == null
                || testSurfaceRegistry == null
                || testAcademySyncEnabled == null
                || testAcademyReadinessMaxAgeMs == null) {
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
            verifyAcademySyncReadiness(
                    testJdbcTemplate,
                    testAcademySyncEnabled,
                    testAcademyReadinessMaxAgeMs
            );
            return;
        }
        verifyConfiguredRuntimeReadiness();
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

        Integer activeAcademyIdentities;
        try {
            activeAcademyIdentities = jdbcTemplate.queryForObject(
                    ACTIVE_ACADEMY_CPF_IDENTITY_COUNT_SQL,
                    Integer.class
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "PostgreSQL Córtex não está pronto para validar a identidade Academy.",
                    exception
            );
        }
        if (activeAcademyIdentities == null || activeAcademyIdentities < 1) {
            throw new IllegalStateException(
                    "Runtime PostgreSQL exige ao menos uma identidade Academy ativa "
                            + "com HMAC atual de CPF."
            );
        }
    }

    private static void verifyAcademySyncReadiness(
            JdbcTemplate jdbcTemplate,
            boolean academySyncEnabled,
            long academyReadinessMaxAgeMs
    ) {
        if (!academySyncEnabled) {
            return;
        }
        if (academyReadinessMaxAgeMs <= 0) {
            throw new IllegalStateException(
                    "Runtime PostgreSQL exige cortex.sync.academy.readiness-max-age-ms "
                            + "maior que zero."
            );
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    LATEST_ACADEMY_SYNC_RUN_SQL,
                    ACADEMY_CONNECTOR_NAME
            );
        } catch (DataAccessException exception) {
            throw redactedAcademyReadinessFailure();
        }

        if (rows.isEmpty()) {
            throw academySyncNotReady();
        }

        AcademySyncRun latestRun;
        try {
            Map<String, Object> row = rows.getFirst();
            latestRun = new AcademySyncRun(
                    String.valueOf(row.get("status")),
                    toLocalDateTime(row.get("finished_at")),
                    toLocalDateTime(row.get("database_now"))
            );
        } catch (RuntimeException exception) {
            throw redactedAcademyReadinessFailure();
        }
        if (latestRun.databaseNow() == null) {
            throw redactedAcademyReadinessFailure();
        }

        LocalDateTime cutoff = latestRun.databaseNow().minus(
                academyReadinessMaxAgeMs,
                ChronoUnit.MILLIS
        );
        if (!"SUCCESS".equals(latestRun.status())
                || latestRun.finishedAt() == null
                || latestRun.finishedAt().isAfter(
                        latestRun.databaseNow()
                )
                || latestRun.finishedAt().isBefore(cutoff)) {
            throw academySyncNotReady();
        }
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalArgumentException("Tipo temporal inesperado.");
    }

    private static IllegalStateException academySyncNotReady() {
        return new IllegalStateException(
                "Runtime PostgreSQL exige uma sincronização Academy recente e bem-sucedida."
        );
    }

    private static IllegalStateException redactedAcademyReadinessFailure() {
        return new IllegalStateException(
                "PostgreSQL Córtex não está pronto para validar a sincronização Academy."
        );
    }

    private record AcademySyncRun(
            String status,
            LocalDateTime finishedAt,
            LocalDateTime databaseNow
    ) {
    }
}

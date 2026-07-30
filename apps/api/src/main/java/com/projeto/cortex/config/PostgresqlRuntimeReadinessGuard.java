package com.projeto.cortex.config;

import com.projeto.cortex.common.RuntimeReleaseEvidence;
import com.projeto.cortex.common.RuntimeReadiness;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
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
    static final long RUNTIME_SNAPSHOT_TTL_NANOS =
            TimeUnit.SECONDS.toNanos(1L);
    static final long RUNTIME_FAILURE_BACKOFF_NANOS =
            TimeUnit.MILLISECONDS.toNanos(250L);
    private static final String ACADEMY_CONNECTOR_NAME = "acad_colaborador_import";
    private static final Pattern FULL_GIT_REVISION = Pattern.compile("[0-9a-f]{40}");

    private static final String COMPLETED_REQUIRED_VERSION_SQL = """
            SELECT COUNT(*)
            FROM public.flyway_schema_history
            WHERE version = '%s'
              AND success = TRUE
            """;

    private static final String ACTIVE_ACADEMY_CPF_IDENTITY_COUNT_SQL = """
            SELECT COUNT(*)
            FROM public.colaborador c
            JOIN public.auth_identity ai ON ai.colaborador_id = c.id
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
            FROM public.source_sync_run
            WHERE connector_name = ?
            ORDER BY started_at DESC, id DESC
            LIMIT 1
            """;

    private static final String RELEASE_EVIDENCE_SQL = """
            SELECT revision, marker
            FROM public.cortex_release_marker
            WHERE revision = ?
            """;

    private final JdbcTemplate testJdbcTemplate;
    private final String testRequiredSchemaVersion;
    private final Boolean testRuntimeReady;
    private final PostgresqlRuntimeSurfaceRegistry testSurfaceRegistry;
    private final Boolean testAcademySyncEnabled;
    private final Long testAcademyReadinessMaxAgeMs;
    private final LongSupplier nanoTime;
    private final ReentrantLock runtimeEvaluationLock = new ReentrantLock();
    private Environment environment;
    private volatile ConfigurableListableBeanFactory beanFactory;
    private volatile JdbcTemplate managedJdbcTemplate;
    private volatile RuntimeSnapshot runtimeSnapshot;
    private volatile RuntimeFailureSnapshot runtimeFailureSnapshot;

    public PostgresqlRuntimeReadinessGuard() {
        this.testJdbcTemplate = null;
        this.testRequiredSchemaVersion = null;
        this.testRuntimeReady = null;
        this.testSurfaceRegistry = null;
        this.testAcademySyncEnabled = null;
        this.testAcademyReadinessMaxAgeMs = null;
        this.nanoTime = System::nanoTime;
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
                DEFAULT_ACADEMY_READINESS_MAX_AGE_MS,
                System::nanoTime
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
        this(
                jdbcTemplate,
                requiredSchemaVersion,
                runtimeReady,
                surfaceRegistry,
                academySyncEnabled,
                academyReadinessMaxAgeMs,
                System::nanoTime
        );
    }

    PostgresqlRuntimeReadinessGuard(
            JdbcTemplate jdbcTemplate,
            String requiredSchemaVersion,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry surfaceRegistry,
            boolean academySyncEnabled,
            long academyReadinessMaxAgeMs,
            LongSupplier nanoTime
    ) {
        this.testJdbcTemplate = jdbcTemplate;
        this.testRequiredSchemaVersion = requiredSchemaVersion;
        this.testRuntimeReady = runtimeReady;
        this.testSurfaceRegistry = surfaceRegistry;
        this.testAcademySyncEnabled = academySyncEnabled;
        this.testAcademyReadinessMaxAgeMs = academyReadinessMaxAgeMs;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
        this.runtimeSnapshot = null;
        this.runtimeFailureSnapshot = null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        this.beanFactory = beanFactory;
        if (testJdbcTemplate == null) {
            verifyConfiguredStartupReadiness();
            return;
        }
        verifyReadiness();
        verifyReleaseEvidenceIfRequired(testJdbcTemplate);
    }

    private void verifyConfiguredStartupReadiness() {
        JdbcTemplate jdbcTemplate = postgresqlJdbcTemplate();
        verifyReadiness(
                jdbcTemplate,
                PostgresqlSchemaVersion.REQUIRED,
                environment.getProperty("cortex.postgresql.runtime-ready", Boolean.class, false),
                new PostgresqlRuntimeSurfaceRegistry()
        );
        verifyReleaseEvidenceIfRequired(jdbcTemplate);
    }

    private void verifyConfiguredRuntimeReadiness(JdbcTemplate jdbcTemplate) {
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
        verifiedRuntimeSnapshot();
    }

    @Override
    public Map<String, String> verifiedPublicEvidence() {
        return verifiedRuntimeSnapshot().publicEvidence();
    }

    @Override
    public Map<String, String> publicEvidence() {
        return verifiedRuntimeSnapshot().publicEvidence();
    }

    private RuntimeSnapshot verifiedRuntimeSnapshot() {
        String releaseRevision = configuredReleaseRevision();
        boolean markerRequired = releaseMarkerRequired();
        long now = nanoTime.getAsLong();
        RuntimeSnapshot snapshot = runtimeSnapshot;
        if (snapshot != null
                && snapshot.isValidFor(releaseRevision, markerRequired, now)) {
            return snapshot;
        }
        RuntimeFailureSnapshot failureSnapshot = runtimeFailureSnapshot;
        if (failureSnapshot != null
                && failureSnapshot.isValidFor(
                        releaseRevision,
                        markerRequired,
                        now
                )) {
            throw transientRuntimeReadinessFailure();
        }

        if (!runtimeEvaluationLock.tryLock()) {
            throw transientRuntimeReadinessFailure();
        }
        try {
            now = nanoTime.getAsLong();
            snapshot = runtimeSnapshot;
            if (snapshot != null
                    && snapshot.isValidFor(releaseRevision, markerRequired, now)) {
                return snapshot;
            }
            failureSnapshot = runtimeFailureSnapshot;
            if (failureSnapshot != null
                    && failureSnapshot.isValidFor(
                            releaseRevision,
                            markerRequired,
                            now
                    )) {
                throw transientRuntimeReadinessFailure();
            }
            try {
                RuntimeSnapshot verified = evaluateRuntimeReadiness(
                        releaseRevision,
                        markerRequired
                );
                runtimeSnapshot = verified;
                runtimeFailureSnapshot = null;
                return verified;
            } catch (RuntimeException exception) {
                runtimeFailureSnapshot = new RuntimeFailureSnapshot(
                        releaseRevision,
                        markerRequired,
                        nanoTime.getAsLong() + RUNTIME_FAILURE_BACKOFF_NANOS
                );
                throw exception;
            }
        } finally {
            runtimeEvaluationLock.unlock();
        }
    }

    private RuntimeSnapshot evaluateRuntimeReadiness(
            String releaseRevision,
            boolean markerRequired
    ) {
        JdbcTemplate jdbcTemplate = effectiveJdbcTemplate();
        Boolean databaseReady;
        try {
            databaseReady = jdbcTemplate.queryForObject("SELECT TRUE", Boolean.class);
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "PostgreSQL indisponível para readiness."
            );
        }
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
        } else {
            verifyConfiguredRuntimeReadiness(jdbcTemplate);
        }
        RuntimeReleaseEvidence releaseEvidence = markerRequired
                ? requireReleaseEvidence(jdbcTemplate, releaseRevision)
                : null;
        return new RuntimeSnapshot(
                releaseRevision,
                markerRequired,
                releaseEvidence,
                nanoTime.getAsLong() + RUNTIME_SNAPSHOT_TTL_NANOS
        );
    }

    private JdbcTemplate effectiveJdbcTemplate() {
        if (testJdbcTemplate != null) {
            return testJdbcTemplate;
        }
        JdbcTemplate cached = managedJdbcTemplate;
        if (cached != null) {
            return cached;
        }
        ConfigurableListableBeanFactory currentBeanFactory = beanFactory;
        if (currentBeanFactory == null) {
            throw managedJdbcTemplateFailure();
        }
        try {
            JdbcTemplate managed = currentBeanFactory
                    .getBeanProvider(JdbcTemplate.class)
                    .getIfAvailable();
            if (managed == null) {
                throw managedJdbcTemplateFailure();
            }
            managedJdbcTemplate = managed;
            return managed;
        } catch (BeansException exception) {
            throw managedJdbcTemplateFailure();
        }
    }

    private JdbcTemplate postgresqlJdbcTemplate() {
        if (environment == null) {
            throw new IllegalStateException("Ambiente PostgreSQL indisponível para a verificação.");
        }
        return new JdbcTemplate(PostgresqlJdbcTlsPolicy.dataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username", ""),
                environment.getProperty("spring.datasource.password", "")
        ));
    }

    private boolean releaseMarkerRequired() {
        if (environment == null) {
            return false;
        }
        String releaseRevision = configuredReleaseRevision();
        return environment.getProperty(
                "cortex.postgresql.release-marker.required",
                Boolean.class,
                false
        ) || (releaseRevision != null && !releaseRevision.isBlank());
    }

    private void verifyReleaseEvidenceIfRequired(JdbcTemplate jdbcTemplate) {
        if (releaseMarkerRequired()) {
            requireReleaseEvidence(jdbcTemplate, configuredReleaseRevision());
        }
    }

    private String configuredReleaseRevision() {
        if (environment == null) {
            return null;
        }
        return environment.getProperty("RENDER_GIT_COMMIT");
    }

    private static RuntimeReleaseEvidence requireReleaseEvidence(
            JdbcTemplate jdbcTemplate,
            String expectedRevision
    ) {
        if (expectedRevision == null
                || !FULL_GIT_REVISION.matcher(expectedRevision).matches()) {
            throw releaseEvidenceFailure();
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    RELEASE_EVIDENCE_SQL,
                    expectedRevision
            );
        } catch (DataAccessException exception) {
            throw releaseEvidenceFailure();
        }
        if (rows == null || rows.size() != 1) {
            throw releaseEvidenceFailure();
        }

        try {
            Map<String, Object> row = rows.getFirst();
            if (!(row.get("revision") instanceof String revision)
                    || !(row.get("marker") instanceof String marker)) {
                throw releaseEvidenceFailure();
            }
            if (!expectedRevision.equals(revision)) {
                throw releaseEvidenceFailure();
            }
            return new RuntimeReleaseEvidence(revision, marker);
        } catch (RuntimeException exception) {
            throw releaseEvidenceFailure();
        }
    }

    private static IllegalStateException releaseEvidenceFailure() {
        return new IllegalStateException(
                "PostgreSQL Córtex não está pronto para validar "
                        + "o marcador público de release."
        );
    }

    private static IllegalStateException managedJdbcTemplateFailure() {
        return new IllegalStateException(
                "PostgreSQL indisponível para readiness."
        );
    }

    private static IllegalStateException transientRuntimeReadinessFailure() {
        return new IllegalStateException(
                "PostgreSQL indisponível para readiness."
        );
    }

    private record RuntimeSnapshot(
            String releaseRevision,
            boolean releaseMarkerRequired,
            RuntimeReleaseEvidence releaseEvidence,
            long expiresAtNanos
    ) {

        private boolean isValidFor(
                String expectedRevision,
                boolean expectedMarkerRequirement,
                long now
        ) {
            return now < expiresAtNanos
                    && releaseMarkerRequired == expectedMarkerRequirement
                    && Objects.equals(releaseRevision, expectedRevision);
        }

        private Map<String, String> publicEvidence() {
            return releaseEvidence == null
                    ? Map.of()
                    : releaseEvidence.asPublicEvidence();
        }
    }

    private record RuntimeFailureSnapshot(
            String releaseRevision,
            boolean releaseMarkerRequired,
            long retryAfterNanos
    ) {

        private boolean isValidFor(
                String expectedRevision,
                boolean expectedMarkerRequirement,
                long now
        ) {
            return now < retryAfterNanos
                    && releaseMarkerRequired == expectedMarkerRequirement
                    && Objects.equals(releaseRevision, expectedRevision);
        }
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

package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlRdoTriggeredPdorFailureIT {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 7, 23);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_rdo_pdor_failure_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate outerTransaction;
    private static PlatformTransactionManager transactionManager;
    private static PdorSnapshotRepository repository;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        outerTransaction = new TransactionTemplate(transactionManager);
        repository = transactionalRepository(transactionManager);
    }

    @Test
    void failureAuditUsesRequiresNewAndSurvivesOuterRollback() throws Exception {
        Transactional boundary = PdorSnapshotRepository.class
                .getMethod(
                        "recordFailureAndMarkCurrentStale",
                        String.class,
                        String.class,
                        String.class,
                        Long.class,
                        PdorTriggerType.class,
                        String.class,
                        Instant.class
                )
                .getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull();
        assertThat(boundary.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

        Fixture fixture = fixture("rollback");
        String attemptedRdoId = id();
        String correlationId = id();

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(status -> {
            insertRdo(attemptedRdoId, fixture.obraId());
            repository.recordFailureAndMarkCurrentStale(
                    correlationId,
                    fixture.obraId(),
                    fixture.snapshotId(),
                    41L,
                    PdorTriggerType.EVENT,
                    "SYSTEM",
                    Instant.parse("2026-07-23T12:00:00Z")
            );
            throw new IllegalStateException("ROLLBACK_OUTER_RDO");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ROLLBACK_OUTER_RDO");

        assertThat(rowCount("rdo", "id", attemptedRdoId)).isZero();
        assertDurableFailure(fixture, correlationId);
    }

    @Test
    void failureForObservedSnapshotDoesNotStaleNewerConcurrentSuccess()
            throws Exception {
        Fixture observed = fixture("observed");
        String newerSnapshotId = id();
        String correlationId = id();
        CountDownLatch newerSuccessWritten = new CountDownLatch(1);
        CountDownLatch releaseNewerSuccess = new CountDownLatch(1);
        CountDownLatch failureStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> success = executor.submit(() -> {
                TransactionTemplate transaction =
                        new TransactionTemplate(transactionManager);
                transaction.executeWithoutResult(status -> {
                    repository.replaceCurrent(snapshot(
                            newerSnapshotId,
                            observed.obraId(),
                            "newer"
                    ));
                    newerSuccessWritten.countDown();
                    await(releaseNewerSuccess);
                });
            });
            assertThat(newerSuccessWritten.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> failure = executor.submit(() -> {
                failureStarted.countDown();
                repository.recordFailureAndMarkCurrentStale(
                        correlationId,
                        observed.obraId(),
                        observed.snapshotId(),
                        43L,
                        PdorTriggerType.EVENT,
                        "SYSTEM",
                        Instant.parse("2026-07-23T12:02:00Z")
                );
            });
            assertThat(failureStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseNewerSuccess.countDown();

            success.get(10, TimeUnit.SECONDS);
            failure.get(10, TimeUnit.SECONDS);
        } finally {
            releaseNewerSuccess.countDown();
            executor.shutdownNow();
        }

        assertThat(rowCount(
                "pdor_calculation_failure",
                "correlation_id",
                correlationId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT is_stale FROM pdor_snapshot WHERE id = ?",
                Boolean.class,
                newerSnapshotId
        )).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT is_current FROM pdor_snapshot WHERE id = ?",
                Boolean.class,
                newerSnapshotId
        )).isTrue();
    }

    @Test
    void failureWithoutObservedSnapshotRecordsAuditWithoutStalingCurrent() {
        Fixture fixture = fixture("no-previous");
        String correlationId = id();

        repository.recordFailureAndMarkCurrentStale(
                correlationId,
                fixture.obraId(),
                null,
                44L,
                PdorTriggerType.EVENT,
                "SYSTEM",
                Instant.parse("2026-07-23T12:03:00Z")
        );

        assertThat(rowCount(
                "pdor_calculation_failure",
                "correlation_id",
                correlationId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT is_stale FROM pdor_snapshot WHERE id = ?",
                Boolean.class,
                fixture.snapshotId()
        )).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT is_current FROM pdor_snapshot WHERE id = ?",
                Boolean.class,
                fixture.snapshotId()
        )).isTrue();
    }

    @Test
    void pdorFailureDoesNotUndoAcceptedRdoAndStoresNoInternalDetails() {
        Fixture fixture = fixture("accepted");
        String acceptedRdoId = id();
        String correlationId = id();
        PdorApplicationService pdor = mock(PdorApplicationService.class);
        when(pdor.calcular(
                fixture.obraId(),
                REFERENCE_DATE,
                PdorTriggerType.EVENT,
                null
        )).thenAnswer(invocation -> {
            repository.recordFailureAndMarkCurrentStale(
                    correlationId,
                    fixture.obraId(),
                    fixture.snapshotId(),
                    42L,
                    PdorTriggerType.EVENT,
                    "SYSTEM",
                    Instant.parse("2026-07-23T12:01:00Z")
            );
            throw new PdorCalculationException(
                    correlationId,
                    new IllegalStateException(
                            "jdbc:postgresql://internal-secret-host"
                    )
            );
        });
        PrevisaoFinanceiraService forecast =
                new PrevisaoFinanceiraService(pdor);

        assertThatCode(() -> outerTransaction.executeWithoutResult(status -> {
            insertRdo(acceptedRdoId, fixture.obraId());
            forecast.recalcularAposMudancaRdo(
                    fixture.obraId(),
                    REFERENCE_DATE,
                    null
            );
        })).doesNotThrowAnyException();

        assertThat(rowCount("rdo", "id", acceptedRdoId)).isOne();
        assertDurableFailure(fixture, correlationId);
        String persistedAudit = jdbc.queryForObject(
                """
                SELECT row_to_json(failure)::text
                FROM pdor_calculation_failure failure
                WHERE correlation_id = ?
                """,
                String.class,
                correlationId
        );
        assertThat(persistedAudit)
                .contains("PDOR_CALCULATION_FAILED")
                .doesNotContain("internal-secret-host")
                .doesNotContain("jdbc:postgresql");
    }

    private static PdorSnapshotRepository transactionalRepository(
            PlatformTransactionManager transactionManager
    ) {
        PdorSnapshotRepository target = new PdorSnapshotRepository(
                jdbc,
                new ObjectMapper()
        );
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager,
                new AnnotationTransactionAttributeSource()
        );
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (PdorSnapshotRepository) proxyFactory.getProxy();
    }

    private static Fixture fixture(String suffix) {
        String obraId = id();
        String snapshotId = id();
        jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, nome)
                VALUES (?, ?, ?)
                """,
                obraId,
                "PDOR-" + suffix,
                "Obra PDOR " + suffix
        );
        repository.replaceCurrent(snapshot(snapshotId, obraId, suffix));
        return new Fixture(obraId, snapshotId);
    }

    private static PdorSnapshot snapshot(
            String snapshotId,
            String obraId,
            String suffix
    ) {
        ObjectMapper mapper = new ObjectMapper();
        LocalDateTime executedAt = LocalDateTime.of(2026, 7, 23, 11, 0);
        PdorSnapshot base = new PdorSnapshot(
                snapshotId,
                obraId,
                "PDOR-" + suffix,
                executedAt,
                REFERENCE_DATE.minusDays(1),
                "PDOR-REVENUE-1",
                "PDOR-ASSUMPTIONS-1",
                PdorExecutionStatus.SUCCESS,
                PdorTriggerType.API,
                null,
                "0".repeat(56) + String.format("%08x", suffix.hashCode()),
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                mapper.createArrayNode(),
                "SIMULATION",
                "CALIBRATED",
                "PRODUCTION",
                "LOW",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                true,
                10_000,
                mapper.createArrayNode(),
                null,
                executedAt
        );
        return base.withRevenueMetadata(
                "PDOR-REVENUE-1",
                List.of(id()),
                40L,
                "COMPLETE_ACCEPTED_EXACT",
                mapper.createObjectNode().put("iterations", 10_000),
                executedAt.toInstant(ZoneOffset.UTC),
                false,
                true
        );
    }

    private static void insertRdo(String rdoId, String obraId) {
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo)
                VALUES (?, ?, 'RDO-PDOR-FAILURE', ?)
                """,
                rdoId,
                obraId,
                REFERENCE_DATE
        );
    }

    private static void assertDurableFailure(
            Fixture fixture,
            String correlationId
    ) {
        assertThat(rowCount(
                "pdor_calculation_failure",
                "correlation_id",
                correlationId
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT is_stale
                FROM pdor_snapshot
                WHERE id = ?
                """,
                Boolean.class,
                fixture.snapshotId()
        )).isTrue();
        assertThat(jdbc.queryForObject(
                """
                SELECT is_current
                FROM pdor_snapshot
                WHERE id = ?
                """,
                Boolean.class,
                fixture.snapshotId()
        )).isFalse();
    }

    private static int rowCount(String table, String column, String value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch.");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(String obraId, String snapshotId) {
    }
}

package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.intelligence.PdorEngine;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.rdos.RdoMemoryPublisher;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PdorApplicationService.class,
        PdorSnapshotPublicationService.class,
        PdorSnapshotRepository.class,
        PrevisaoFinanceiraService.class,
        RdoWorkflowService.class,
        PostgresqlPdorTransactionalBoundaryIT.JsonConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresqlPdorTransactionalBoundaryIT {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 8, 14);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_pdor_transaction_boundary_it");

    @Autowired
    private PdorApplicationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RdoWorkflowService workflowService;

    @MockBean
    private PdorInputLoader inputLoader;

    @MockBean
    private CortexOperationalMemoryService memoryService;

    @MockBean
    private PdorExecutionInitiatorResolver initiatorResolver;

    @MockBean
    private ObraOperabilityGuard operabilityGuard;

    @MockBean
    private RdoQueryService queryService;

    @MockBean
    private RdoMemoryPublisher rdoMemoryPublisher;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add(
                "spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver"
        );
    }

    @BeforeEach
    void configureCollaborators() {
        reset(
                inputLoader,
                memoryService,
                initiatorResolver,
                operabilityGuard,
                queryService,
                rdoMemoryPublisher
        );
        when(inputLoader.load(any(Obra.class), any()))
                .thenAnswer(invocation -> insufficientBundle(
                        invocation.getArgument(0)
                ));
        when(initiatorResolver.resolve())
                .thenReturn(PdorExecutionInitiator.process());
    }

    @Test
    void cancellingJanuaryRdoKeepsFebruaryAsCurrentReferenceDate() {
        LocalDate january = LocalDate.of(2026, 1, 15);
        LocalDate february = LocalDate.of(2026, 2, 20);
        String obraId = insertWorksite("old-rdo-cancellation");
        String januaryRdoId = insertRdo(obraId, january, "RDO-JAN");
        insertRdo(obraId, february, "RDO-FEV");

        when(inputLoader.load(
                any(Obra.class),
                nullable(LocalDate.class)
        )).thenAnswer(invocation -> {
            Obra obra = invocation.getArgument(0);
            LocalDate requested = invocation.getArgument(1);
            LocalDate resolved = requested != null
                    ? requested
                    : jdbc.queryForObject(
                            """
                            SELECT MAX(data_rdo)
                            FROM rdo
                            WHERE obra_id = ?
                              AND cancelado_em IS NULL
                            """,
                            LocalDate.class,
                            obra.getId()
                    );
            return insufficientBundle(obra, resolved);
        });

        PdorResultadoResponse before = service.calcular(
                obraId, null, PdorTriggerType.EVENT, null
        );
        assertThat(before.dataReferencia()).isEqualTo(february);

        RdoResponse cancelled = mock(RdoResponse.class);
        when(cancelled.obraId()).thenReturn(obraId);
        when(cancelled.dataRdo()).thenReturn(january);
        when(cancelled.numeroRdo()).thenReturn("RDO-JAN");
        when(cancelled.status()).thenReturn("CANCELADA");
        when(queryService.buscarPorId(januaryRdoId)).thenReturn(cancelled);

        workflowService.cancelar(januaryRdoId);

        PdorResultadoResponse current = service.buscarAtual(obraId);
        assertThat(current.dataReferencia()).isEqualTo(february);
        assertThat(current.id()).isNotEqualTo(before.id());
    }

    @Test
    @Timeout(20)
    void concurrentCallsForSameInputsConvergeToOneCurrentSnapshot()
            throws Exception {
        String obraId = insertWorksite("concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        assertThat(AopUtils.isAopProxy(service)).isTrue();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PdorResultadoResponse> first = executor.submit(() -> {
                ready.countDown();
                await(start);
                return service.calcular(
                        obraId, REFERENCE_DATE, PdorTriggerType.EVENT, null
                );
            });
            Future<PdorResultadoResponse> second = executor.submit(() -> {
                ready.countDown();
                await(start);
                return service.calcular(
                        obraId, REFERENCE_DATE, PdorTriggerType.EVENT, null
                );
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            PdorResultadoResponse firstResult = first.get(10, TimeUnit.SECONDS);
            PdorResultadoResponse secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.id()).isEqualTo(secondResult.id());
            assertThat(List.of(
                    firstResult.snapshotExistente(),
                    secondResult.snapshotExistente()
            )).containsExactlyInAnyOrder(false, true);
        } finally {
            start.countDown();
        }

        assertThat(snapshotCount(obraId, true)).isOne();
        assertThat(snapshotCount(obraId, false)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pdor_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isOne();
    }

    @Test
    @Timeout(20)
    void publicationFailureRollsBackBeforePersistingFailureWithoutDeadlock()
            throws Exception {
        String obraId = insertWorksite("failure");
        doAnswer(invocation -> {
            jdbc.execute("SELECT 1 / 0");
            return null;
        })
                .when(memoryService)
                .registrarObjeto(
                        eq("PDOR"),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("PDOR")
                );

        PdorCalculationException failure;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<PdorCalculationException> attempt = executor.submit(() -> {
                try {
                    service.calcular(
                            obraId,
                            REFERENCE_DATE,
                            PdorTriggerType.EVENT,
                            null
                    );
                    throw new AssertionError("O cálculo deveria falhar.");
                } catch (PdorCalculationException expected) {
                    return expected;
                }
            });
            failure = getFailure(attempt);
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pdor_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pdor_calculation_failure
                WHERE correlation_id = ?
                  AND obra_id = ?
                """,
                Integer.class,
                failure.correlationId(),
                obraId
        )).isOne();
    }

    private String insertWorksite(String suffix) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, fonte_criacao
                ) VALUES (?, ?, ?, 'ATIVA', 'TEST')
                """,
                id,
                "PDOR-TX-" + suffix + "-" + id,
                "Obra transacional " + suffix
        );
        return id;
    }

    private String insertRdo(
            String obraId,
            LocalDate dataRdo,
            String numeroRdo
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo, status
                ) VALUES (?, ?, ?, ?, 'ENVIADO')
                """,
                id,
                obraId,
                numeroRdo,
                dataRdo
        );
        return id;
    }

    private int snapshotCount(String obraId, boolean current) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pdor_snapshot
                WHERE obra_id = ?
                  AND is_current = ?
                """,
                Integer.class,
                obraId,
                current
        );
        return count == null ? 0 : count;
    }

    private static PdorCalculationException getFailure(
            Future<PdorCalculationException> attempt
    ) throws Exception {
        try {
            return attempt.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static PdorInputBundle insufficientBundle(Obra obra) {
        return insufficientBundle(obra, REFERENCE_DATE);
    }

    private static PdorInputBundle insufficientBundle(
            Obra obra,
            LocalDate referenceDate
    ) {
        return new PdorInputBundle(
                obra.getId(),
                obra.getCodigoContrato(),
                referenceDate,
                Map.of(),
                Map.of(),
                List.of("Receita canônica ausente."),
                List.of("measuredRevenue", "validatedRevenue"),
                null,
                PdorEngine.HistoricalSeries.EMPTY,
                List.of(),
                List.of(),
                0L,
                "NO_ACCEPTED_EVIDENCE"
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout aguardando concorrência.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concorrência interrompida.", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}

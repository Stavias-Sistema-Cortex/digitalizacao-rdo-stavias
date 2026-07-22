package com.projeto.cortex.financeiro.revenue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.rdos.RdoContextResponse;
import com.projeto.cortex.rdos.RdoContextService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoHistoricalImportServiceCommand;
import com.projeto.cortex.rdos.RdoOperationalDetailService;
import com.projeto.cortex.rdos.RdoResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlRevenueEvidenceIT {

    private static final LocalDate RDO_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_revenue_evidence_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper mapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void acceptedExecutionFreezesExactRevenueAndOntologyAcrossPriceHistoryAndReplay()
            throws Exception {
        Fixture fixture = fixture("ACCEPTED");
        String executionId = id();
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                executionId, fixture.serviceId(), fixture.priceId(),
                "0.333", "M2", "VALIDADA", false, false, "Original"
        );

        RdoResponse.ServicoExecutadoItem first = replace(
                fixture.rdoId(), fixture.obraId(), List.of(accepted)
        ).getFirst();

        assertThat(first.serviceId()).isEqualTo(fixture.serviceId());
        assertThat(first.priceVersionId()).isEqualTo(fixture.priceId());
        assertThat(jdbc.queryForMap("""
                SELECT unit_price_snapshot, currency, revenue_amount
                FROM execucao_servico_rdo WHERE id = ?
                """, executionId))
                .containsEntry("unit_price_snapshot", new BigDecimal("10.0000"))
                .containsEntry("currency", "BRL")
                .containsEntry("revenue_amount", new BigDecimal("3.33"));
        assertThat(first.revenueCoverageCode()).isEqualTo("ACCEPTED_EXACT");
        assertThat(first.revenueEvidenceId()).isNotBlank();
        assertThat(first.revenueEventId()).isNotBlank();
        assertThat(first.acceptedAt()).isNotNull();

        insertPrice(
                fixture.obraId(), fixture.serviceId(), fixture.actorId(),
                "M2", "BRL", "12.0000", RDO_DATE.plusDays(1), null,
                2, fixture.priceId()
        );
        RdoResponse.ServicoExecutadoItem replay = replace(
                fixture.rdoId(), fixture.obraId(), List.of(accepted)
        ).getFirst();

        assertThat(replay.revenueEvidenceId()).isEqualTo(first.revenueEvidenceId());
        assertThat(jdbc.queryForMap("""
                SELECT unit_price_snapshot, revenue_amount
                FROM execucao_servico_rdo WHERE id = ?
                """, executionId))
                .containsEntry("unit_price_snapshot", new BigDecimal("10.0000"))
                .containsEntry("revenue_amount", new BigDecimal("3.33"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cortex_evento_operacional WHERE id = ?",
                Integer.class, first.revenueEventId()
        )).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT tipo_entidade, tipo_evento
                FROM cortex_evento_operacional WHERE id = ?
                """, first.revenueEventId()))
                .containsEntry("tipo_entidade", "RDO_EXECUTION")
                .containsEntry("tipo_evento", "RDO_SERVICE_EXECUTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_objeto
                WHERE (tipo_entidade, entidade_id) IN (
                    ('SERVICE', ?),
                    ('SERVICE_PRICE_VERSION', ?),
                    ('RDO_SERVICE_EXECUTED', ?),
                    ('REVENUE_EVIDENCE', ?)
                )
                """, Integer.class, fixture.serviceId(), fixture.priceId(),
                executionId, first.revenueEvidenceId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_relacao
                WHERE origem_tipo = 'RDO_SERVICE_EXECUTED'
                  AND origem_id = ?
                  AND tipo_relacao IN (
                      'PRICED_BY', 'EXECUTED_IN', 'EXECUTES_SERVICE',
                      'GENERATES_REVENUE'
                  )
                  AND ativa = TRUE
                """, Integer.class, executionId)).isEqualTo(4);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE execucao_servico_rdo SET observacoes = 'tampered' WHERE id = ?",
                executionId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_EVIDENCE_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM execucao_servico_rdo WHERE id = ?", executionId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_EVIDENCE_IMMUTABLE");
    }

    @Test
    void registeredRejectedAndReworkStayUnpricedZeroAndRegisteredCanBeEditedOrRemoved()
            throws Exception {
        Fixture fixture = fixture("DRAFT");
        String registeredId = id();
        RdoResponse.ServicoExecutadoItem registered = replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        registeredId, fixture.serviceId(), fixture.priceId(),
                        "1.000", "M2", "REGISTRADA", false, false, "v1"
                ))
        ).getFirst();
        assertThat(registered.revenueEvidenceId()).isNull();
        assertThat(registered.revenueCoverageCode()).isEqualTo("UNPRICED_REGISTERED");
        assertThat(jdbc.queryForMap(
                """
                SELECT price_version_id, currency, unit_price_snapshot
                FROM execucao_servico_rdo WHERE id = ?
                """, registeredId
        )).allSatisfy((column, value) -> assertThat(value).isNull());
        assertThat(jdbc.queryForObject(
                "SELECT revenue_amount FROM execucao_servico_rdo WHERE id = ?",
                BigDecimal.class, registeredId
        )).isEqualByComparingTo("0.00");

        RdoResponse.ServicoExecutadoItem edited = replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        registeredId, fixture.serviceId(), fixture.priceId(),
                        "2.500", "M2", "REGISTRADA", false, false, "v2"
                ))
        ).getFirst();
        assertThat(edited.quantidadeExecutada()).isEqualByComparingTo("2.500");
        assertThat(edited.observacoes()).isEqualTo("v2");
        replace(fixture.rdoId(), fixture.obraId(), List.of());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, registeredId
        )).isZero();

        for (RdoCreateRequest.ServicoExecutadoItem zero : List.of(
                item(id(), fixture.serviceId(), null, "4", "M2",
                        "REJEITADA", false, true, null),
                item(id(), fixture.serviceId(), null, "5", "M2",
                        "CANCELADA", false, false, null),
                item(id(), fixture.serviceId(), null, "6", "M2",
                        "VALIDADA", true, false, null),
                item(id(), fixture.serviceId(), null, "7", "M2",
                        "VALIDADA", false, true, null)
        )) {
            RdoResponse.ServicoExecutadoItem persisted = replace(
                    fixture.rdoId(), fixture.obraId(), List.of(zero)
            ).getFirst();
            assertThat(persisted.revenueEvidenceId()).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT revenue_amount FROM execucao_servico_rdo WHERE id = ?",
                    BigDecimal.class, zero.id()
            )).isEqualByComparingTo("0.00");
            replace(fixture.rdoId(), fixture.obraId(), List.of());
        }
    }

    @Test
    void acceptedOmissionOrTamperFailsBeforeMutatingRegisteredSibling()
            throws Exception {
        Fixture fixture = fixture("TAMPER");
        String acceptedId = id();
        String registeredId = id();
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                acceptedId, fixture.serviceId(), fixture.priceId(),
                "1", "M2", "VALIDADA", false, false, "accepted"
        );
        RdoCreateRequest.ServicoExecutadoItem registered = item(
                registeredId, fixture.serviceId(), fixture.priceId(),
                "2", "M2", "REGISTRADA", false, false, "registered"
        );
        replace(fixture.rdoId(), fixture.obraId(), List.of(accepted, registered));

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        registeredId, fixture.serviceId(), fixture.priceId(),
                        "9", "M2", "REGISTRADA", false, false, "changed"
                ))
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_ACCEPTED_EXECUTION_OMITTED");
        assertThat(quantity(registeredId)).isEqualByComparingTo("2.000");

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(
                        item(acceptedId, fixture.serviceId(), fixture.priceId(),
                                "1.001", "M2", "VALIDADA", false, false, "accepted"),
                        registered
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_ACCEPTED_EXECUTION_IMMUTABLE");
        assertThat(quantity(acceptedId)).isEqualByComparingTo("1.000");
        assertThat(quantity(registeredId)).isEqualByComparingTo("2.000");

        RdoCreateRequest.ServicoExecutadoItem legacyTamper =
                new RdoCreateRequest.ServicoExecutadoItem(
                        accepted.id(), accepted.serviceId(), accepted.priceVersionId(),
                        accepted.servicoNome(), id(), accepted.quantidadeExecutada(),
                        accepted.unidade(), accepted.trechoInicial(), accepted.trechoFinal(),
                        accepted.localizacao(), accepted.turno(), accepted.statusValidacao(),
                        accepted.retrabalho(), accepted.producaoRejeitada(),
                        accepted.observacoes()
                );
        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(legacyTamper, registered)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_ACCEPTED_EXECUTION_IMMUTABLE");
    }

    @Test
    void mixedPayloadRollsBackOnMissingCrossWorksiteOrCancelledPrice()
            throws Exception {
        Fixture fixture = fixture("ROLLBACK");
        Fixture foreign = fixture("FOREIGN");
        String validExecution = id();

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(
                        item(validExecution, fixture.serviceId(), fixture.priceId(),
                                "1", "M2", "VALIDADA", false, false, null),
                        item(id(), foreign.serviceId(), foreign.priceId(),
                                "1", "M2", "VALIDADA", false, false, null)
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_PRICE_SCOPE_INVALID");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE rdo_id = ?",
                Integer.class, fixture.rdoId()
        )).isZero();

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        id(), fixture.serviceId(), null,
                        "1", "M2", "VALIDADA", false, false, null
                ))
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_PRICE_REQUIRED");

        cancelPrice(fixture.priceId(), fixture.obraId(), fixture.actorId(), RDO_DATE);
        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        id(), fixture.serviceId(), fixture.priceId(),
                        "1", "M2", "VALIDADA", false, false, null
                ))
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_PRICE_STALE");
    }

    @Test
    void concurrentExactReplayKeepsOneEvidenceAndOneEvent() throws Exception {
        Fixture fixture = fixture("CONCURRENT");
        String executionId = id();
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                executionId, fixture.serviceId(), fixture.priceId(),
                "10", "M2", "VALIDADA", false, false, null
        );
        RdoResponse.ServicoExecutadoItem created = replace(
                fixture.rdoId(), fixture.obraId(), List.of(accepted)
        ).getFirst();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<RdoResponse.ServicoExecutadoItem>> first = executor.submit(() -> {
                start.await();
                return replace(fixture.rdoId(), fixture.obraId(), List.of(accepted));
            });
            Future<List<RdoResponse.ServicoExecutadoItem>> second = executor.submit(() -> {
                start.await();
                return replace(fixture.rdoId(), fixture.obraId(), List.of(accepted));
            });
            start.countDown();
            assertThat(first.get()).singleElement();
            assertThat(second.get()).singleElement();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cortex_evento_operacional WHERE id = ?",
                Integer.class, created.revenueEventId()
        )).isOne();
    }

    @Test
    void ontologyPublisherFailureRollsBackExecutionEvidenceAndEvent() throws Exception {
        Fixture fixture = fixture("ONTOLOGY-ROLLBACK");
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                id(), fixture.serviceId(), fixture.priceId(),
                "2", "M2", "VALIDADA", false, false, null
        );
        RevenueOntologyPublisher publisher = mock(RevenueOntologyPublisher.class);
        doThrow(new IllegalStateException("ontology unavailable"))
                .when(publisher).publishAccepted(org.mockito.ArgumentMatchers.any());
        RdoOperationalDetailService service = new RdoOperationalDetailService(
                jdbc, memoryService(), publisher
        );

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored ->
                service.substituirDetalhes(
                        fixture.rdoId(), fixture.obraId(), null, RDO_DATE,
                        "DIURNO", List.of(accepted), List.of()
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ontology unavailable");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, accepted.id()
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cortex_evento_operacional WHERE entidade_id = ?",
                Integer.class, accepted.id()
        )).isZero();
    }

    @Test
    void databaseRejectsAcceptedSnapshotAndRevenueTamperingAtomically() {
        Fixture fixture = fixture("DB-ARITHMETIC");
        String snapshotTamper = id();
        String revenueTamper = id();

        assertThatThrownBy(() -> insertAcceptedExecution(
                fixture, snapshotTamper, "1.000", "9.0000", "9.00"
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_SNAPSHOT_MISMATCH");
        assertThat(executionCount(snapshotTamper)).isZero();

        assertThatThrownBy(() -> insertAcceptedExecution(
                fixture, revenueTamper, "1.000", "10.0000", "9.99"
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_AMOUNT_MISMATCH");
        assertThat(executionCount(revenueTamper)).isZero();
    }

    @Test
    void databaseEnforcesHalfUpRevenueRounding() {
        Fixture fixture = fixture("DB-ROUNDING", "1.0050");
        String wrongRounding = id();
        String exactRounding = id();

        assertThatThrownBy(() -> insertAcceptedExecution(
                fixture, wrongRounding, "1.000", "1.0050", "1.00"
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_AMOUNT_MISMATCH");
        assertThat(executionCount(wrongRounding)).isZero();

        insertAcceptedExecution(
                fixture, exactRounding, "1.000", "1.0050", "1.01"
        );
        assertThat(jdbc.queryForObject(
                "SELECT revenue_amount FROM execucao_servico_rdo WHERE id = ?",
                BigDecimal.class, exactRounding
        )).isEqualByComparingTo("1.01");
    }

    @Test
    void databaseRejectsPriceFieldsOnUnpricedExecution() {
        Fixture fixture = fixture("DB-UNPRICED");
        String executionId = id();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    price_version_id, quantidade_executada, unidade_medida,
                    data_execucao, status_validacao, estado_receita,
                    retrabalho, producao_rejeitada, fonte, chave_execucao,
                    unit_price_snapshot, currency, revenue_amount,
                    revenue_coverage_code, revenue_evidence_id,
                    revenue_event_id, accepted_at
                ) VALUES (?, ?, ?, 'Unpriced tamper', ?, ?, 1, 'M2', ?,
                          'REGISTRADA', 'PRODUCAO_REGISTRADA', FALSE, FALSE,
                          'SQL_TEST', ?, NULL, 'BRL', 0,
                          'UNPRICED_REGISTERED', NULL, NULL, NULL)
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), fixture.priceId(), RDO_DATE,
                executionKey(executionId)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_UNPRICED_FIELDS_FORBIDDEN");
        assertThat(executionCount(executionId)).isZero();
    }

    @Test
    void acceptedEvidenceBlocksRetroactiveSupersessionButAllowsFutureSuccessor()
            throws Exception {
        Fixture fixture = fixture("SUPERSESSION-GUARD");
        String executionId = id();
        RdoResponse.ServicoExecutadoItem accepted = replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        executionId, fixture.serviceId(), fixture.priceId(),
                        "2", "M2", "VALIDADA", false, false, null
                ))
        ).getFirst();

        assertThatThrownBy(() -> insertPrice(
                fixture.obraId(), fixture.serviceId(), fixture.actorId(),
                "M2", "BRL", "12.0000", RDO_DATE, null,
                2, fixture.priceId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("SERVICE_PRICE_SUPERSESSION_ACCEPTED_EVIDENCE");

        String future = insertPrice(
                fixture.obraId(), fixture.serviceId(), fixture.actorId(),
                "M2", "BRL", "12.0000", RDO_DATE.plusDays(1), null,
                2, fixture.priceId()
        );
        assertThat(future).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT unit_price_snapshot FROM execucao_servico_rdo WHERE id = ?",
                BigDecimal.class, executionId
        )).isEqualByComparingTo("10.0000");
        assertThat(accepted.revenueEvidenceId()).isNotBlank();
    }

    @Test
    void acceptedEvidenceBlocksRetroactiveCancellation() throws Exception {
        Fixture fixture = fixture("CANCELLATION-GUARD");
        String executionId = id();
        replace(fixture.rdoId(), fixture.obraId(), List.of(item(
                executionId, fixture.serviceId(), fixture.priceId(),
                "1", "M2", "VALIDADA", false, false, null
        )));

        assertThatThrownBy(() -> cancelPrice(
                fixture.priceId(), fixture.obraId(), fixture.actorId(),
                RDO_DATE.minusDays(1)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("SERVICE_PRICE_CANCELLATION_ACCEPTED_EVIDENCE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM service_price_version_cancellation
                WHERE price_version_id = ?
                """, Integer.class, fixture.priceId())).isZero();
    }

    @Test
    void acceptedCommitWinsRaceAndRejectsWaitingSupersession() throws Exception {
        Fixture fixture = fixture("RACE-ACCEPT-SUPERSEDE");
        String executionId = id();
        CountDownLatch acceptedInserted = new CountDownLatch(1);
        CountDownLatch allowAcceptedCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> accepted = executor.submit(() ->
                    transactions.executeWithoutResult(ignored -> {
                        insertAcceptedExecution(
                                fixture, executionId, "1.000", "10.0000", "10.00"
                        );
                        acceptedInserted.countDown();
                        awaitLatch(allowAcceptedCommit);
                    })
            );
            awaitLatch(acceptedInserted);

            Future<?> supersession = executor.submit(() -> {
                assertThatThrownBy(() -> insertPrice(
                        fixture.obraId(), fixture.serviceId(), fixture.actorId(),
                        "M2", "BRL", "12.0000", RDO_DATE, null,
                        2, fixture.priceId()
                )).isInstanceOf(DataAccessException.class)
                        .hasMessageContaining(
                                "SERVICE_PRICE_SUPERSESSION_ACCEPTED_EVIDENCE"
                        );
            });

            try {
                assertBlockedOnAdvisoryLock(supersession);
            } finally {
                allowAcceptedCommit.countDown();
            }
            accepted.get(10, TimeUnit.SECONDS);
            supersession.get(10, TimeUnit.SECONDS);
        }

        assertThat(executionCount(executionId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version WHERE supersedes_id = ?",
                Integer.class, fixture.priceId()
        )).isZero();
    }

    @Test
    void supersessionCommitWinsRaceAndRejectsWaitingAcceptance() throws Exception {
        Fixture fixture = fixture("RACE-SUPERSEDE-ACCEPT");
        String executionId = id();
        CountDownLatch successorInserted = new CountDownLatch(1);
        CountDownLatch allowSuccessorCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> supersession = executor.submit(() ->
                    transactions.executeWithoutResult(ignored -> {
                        insertPrice(
                                fixture.obraId(), fixture.serviceId(), fixture.actorId(),
                                "M2", "BRL", "12.0000", RDO_DATE, null,
                                2, fixture.priceId()
                        );
                        successorInserted.countDown();
                        awaitLatch(allowSuccessorCommit);
                    })
            );
            awaitLatch(successorInserted);

            Future<?> acceptance = executor.submit(() -> {
                assertThatThrownBy(() -> insertAcceptedExecution(
                        fixture, executionId, "1.000", "10.0000", "10.00"
                )).isInstanceOf(DataAccessException.class)
                        .hasMessageContaining("RDO_REVENUE_PRICE_STALE");
            });

            try {
                assertBlockedOnAdvisoryLock(acceptance);
            } finally {
                allowSuccessorCommit.countDown();
            }
            supersession.get(10, TimeUnit.SECONDS);
            acceptance.get(10, TimeUnit.SECONDS);
        }

        assertThat(executionCount(executionId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version WHERE supersedes_id = ?",
                Integer.class, fixture.priceId()
        )).isOne();
    }

    @Test
    void acceptedCommitWinsRaceAndRejectsWaitingCancellation() throws Exception {
        Fixture fixture = fixture("RACE-ACCEPT-CANCEL");
        String executionId = id();
        CountDownLatch acceptedInserted = new CountDownLatch(1);
        CountDownLatch allowAcceptedCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> accepted = executor.submit(() ->
                    transactions.executeWithoutResult(ignored -> {
                        insertAcceptedExecution(
                                fixture, executionId, "1.000", "10.0000", "10.00"
                        );
                        acceptedInserted.countDown();
                        awaitLatch(allowAcceptedCommit);
                    })
            );
            awaitLatch(acceptedInserted);

            Future<?> cancellation = executor.submit(() -> {
                assertThatThrownBy(() -> cancelPrice(
                        fixture.priceId(), fixture.obraId(), fixture.actorId(),
                        RDO_DATE.minusDays(1)
                )).isInstanceOf(DataAccessException.class)
                        .hasMessageContaining(
                                "SERVICE_PRICE_CANCELLATION_ACCEPTED_EVIDENCE"
                        );
            });

            try {
                assertBlockedOnAdvisoryLock(cancellation);
            } finally {
                allowAcceptedCommit.countDown();
            }
            accepted.get(10, TimeUnit.SECONDS);
            cancellation.get(10, TimeUnit.SECONDS);
        }

        assertThat(executionCount(executionId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version_cancellation "
                        + "WHERE price_version_id = ?",
                Integer.class, fixture.priceId()
        )).isZero();
    }

    @Test
    void cancellationCommitWinsRaceAndRejectsWaitingAcceptance() throws Exception {
        Fixture fixture = fixture("RACE-CANCEL-ACCEPT");
        String executionId = id();
        CountDownLatch cancellationInserted = new CountDownLatch(1);
        CountDownLatch allowCancellationCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = executor.submit(() ->
                    transactions.executeWithoutResult(ignored -> {
                        cancelPrice(
                                fixture.priceId(), fixture.obraId(), fixture.actorId(),
                                RDO_DATE.minusDays(1)
                        );
                        cancellationInserted.countDown();
                        awaitLatch(allowCancellationCommit);
                    })
            );
            awaitLatch(cancellationInserted);

            Future<?> acceptance = executor.submit(() -> {
                assertThatThrownBy(() -> insertAcceptedExecution(
                        fixture, executionId, "1.000", "10.0000", "10.00"
                )).isInstanceOf(DataAccessException.class)
                        .hasMessageContaining("RDO_REVENUE_PRICE_STALE");
            });

            try {
                assertBlockedOnAdvisoryLock(acceptance);
            } finally {
                allowCancellationCommit.countDown();
            }
            cancellation.get(10, TimeUnit.SECONDS);
            acceptance.get(10, TimeUnit.SECONDS);
        }

        assertThat(executionCount(executionId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version_cancellation "
                        + "WHERE price_version_id = ?",
                Integer.class, fixture.priceId()
        )).isOne();
    }

    @Test
    void contextReturnsRealCatalogPriceChoicesExactCoverageAndRevision() {
        Fixture fixture = fixture("CONTEXT");

        RdoContextResponse context = new RdoContextService(jdbc)
                .buscarContexto(fixture.obraId(), RDO_DATE);

        assertThat(context.serviceCatalog())
                .filteredOn(service -> service.id().equals(fixture.serviceId()))
                .singleElement()
                .satisfies(service -> assertThat(service.priceChoices())
                        .singleElement()
                        .satisfies(price -> {
                            assertThat(price.id()).isEqualTo(fixture.priceId());
                            assertThat(price.unit()).isEqualTo("M2");
                            assertThat(price.version()).isEqualTo(1);
                        }));
        assertThat(context.coverage().serviceCatalog().status()).isEqualTo("COMPLETE");
        assertThat(context.coverage().priceCatalog().status()).isEqualTo("COMPLETE");
        assertThat(context.coverage().serviceCatalog().complete()).isTrue();
        assertThat(context.coverage().priceCatalog().complete()).isTrue();
        assertThat(context.freshness().catalogRevision()).isPositive();
    }

    @Test
    void historicalImportIsProvenanceGatedAtomicAndUnavailableToNormalRdoPayload()
            throws Exception {
        Fixture fixture = fixture("HISTORICAL");
        String importId = id();
        String executionId = id();
        String legacyItemId = id();
        insertHistoricalImport(importId, fixture.rdoId());
        insertLegacyContractItem(legacyItemId, fixture);
        RdoCreateRequest.ServicoExecutadoItem normalPayload =
                new RdoCreateRequest.ServicoExecutadoItem(
                        executionId, null, null, "Legacy asphalt service", null,
                        new BigDecimal("3.250"), "M2", null, null,
                        "KM 2", "DIURNO", "REGISTRADA", false, false,
                        "historical source"
                );

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(normalPayload)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_SERVICE_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isZero();

        RdoHistoricalImportServiceCommand command = historicalCommand(
                importId, fixture, executionId, legacyItemId
        );
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            detailService().substituirServicoImportadoHistorico(command);
            throw new IllegalStateException("force outer rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("force outer rollback");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isZero();

        RdoResponse.ServicoExecutadoItem imported = transactions.execute(
                ignored -> detailService()
                        .substituirServicoImportadoHistorico(command)
        );
        assertThat(imported).isNotNull();
        assertThat(imported.serviceId()).isNull();
        assertThat(imported.priceVersionId()).isNull();
        assertThat(imported.revenueCoverageCode())
                .isEqualTo("HISTORICAL_UNPRICED");
        assertThat(imported.revenueEvidenceId()).isNull();
        assertThat(imported.revenueEventId()).isNull();
        assertThat(imported.acceptedAt()).isNull();
        assertThat(jdbc.queryForMap("""
                SELECT service_id, price_version_id, unit_price_snapshot, currency,
                       revenue_evidence_id, revenue_event_id, accepted_at
                FROM execucao_servico_rdo WHERE id = ?
                """, executionId)).allSatisfy(
                        (column, value) -> assertThat(value)
                                .as("%s must be null", column).isNull()
                );
        assertThat(jdbc.queryForMap("""
                SELECT revenue_amount, revenue_coverage_code, fonte
                FROM execucao_servico_rdo WHERE id = ?
                """, executionId))
                .containsEntry("revenue_coverage_code", "HISTORICAL_UNPRICED")
                .containsEntry("fonte", "IMPORTACAO_HISTORICA")
                .containsEntry("revenue_amount", new BigDecimal("0.00"));

        RdoHistoricalImportServiceCommand forged = new RdoHistoricalImportServiceCommand(
                command.rdoId(), command.obraId(), id(), command.fileName(),
                command.sheetName(), command.rowNumber(), command.executionId(),
                command.serviceName(), command.itemContractId(), command.quantity(),
                command.unit(), command.initialSegment(), command.finalSegment(),
                command.location(), command.turn(), command.observations()
        );
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                ignored -> detailService().substituirServicoImportadoHistorico(forged)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_HISTORICAL_IMPORT_PROVENANCE_INVALID");
        assertThat(quantity(executionId)).isEqualByComparingTo("3.250");
    }

    private static List<RdoResponse.ServicoExecutadoItem> replace(
            String rdoId,
            String obraId,
            List<RdoCreateRequest.ServicoExecutadoItem> services
    ) {
        return transactions.execute(status -> detailService().substituirDetalhes(
                rdoId, obraId, null, RDO_DATE, "DIURNO", services, List.of()
        ).servicosExecutados());
    }

    private static RdoOperationalDetailService detailService() {
        return new RdoOperationalDetailService(jdbc, memoryService());
    }

    private static CortexOperationalMemoryService memoryService() {
        return new CortexOperationalMemoryService(
                jdbc, mapper, mock(ApplicationEventPublisher.class)
        );
    }

    private static Fixture fixture(String suffix) {
        return fixture(suffix, "10.0000");
    }

    private static Fixture fixture(String suffix, String unitPrice) {
        String obraId = id();
        String actorId = id();
        String rdoId = id();
        String serviceId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId, "CTR-" + suffix + "-" + obraId, "Obra " + suffix
        );
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, ?, 'ALFA')
                """, actorId, actorId, "Actor " + suffix);
        jdbc.update(
                "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, ?, ?)",
                rdoId, obraId, "RDO-" + suffix, RDO_DATE
        );
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, serviceId, "SERVICE." + suffix + "."
                        + serviceId.substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                "Service " + suffix, obraId, actorId);
        String priceId = insertPrice(
                obraId, serviceId, actorId, "M2", "BRL", unitPrice,
                RDO_DATE.minusDays(10), null, 1, null
        );
        return new Fixture(obraId, actorId, rdoId, serviceId, priceId);
    }

    private static void insertAcceptedExecution(
            Fixture fixture,
            String executionId,
            String quantity,
            String snapshot,
            String revenue
    ) {
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    price_version_id, quantidade_executada, unidade_medida,
                    data_execucao, status_validacao, estado_receita,
                    retrabalho, producao_rejeitada, fonte, chave_execucao,
                    unit_price_snapshot, currency, revenue_amount,
                    revenue_coverage_code, revenue_evidence_id,
                    revenue_event_id, accepted_at
                ) VALUES (?, ?, ?, 'Accepted SQL execution', ?, ?, ?, 'M2', ?,
                          'VALIDADA', 'RECEITA_MEDIDA', FALSE, FALSE,
                          'SQL_TEST', ?, ?, 'BRL', ?, 'ACCEPTED_EXACT',
                          ?, ?, now())
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), fixture.priceId(), new BigDecimal(quantity),
                RDO_DATE, executionKey(executionId), new BigDecimal(snapshot),
                new BigDecimal(revenue), id(), id());
    }

    private static int executionCount(String executionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        );
    }

    private static void assertBlockedOnAdvisoryLock(Future<?> future) {
        assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting for concurrent test latch"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for concurrent test latch", exception
            );
        }
    }

    private static String executionKey(String executionId) {
        return executionId.replace("-", "").repeat(2);
    }

    private static void insertHistoricalImport(String importId, String rdoId) {
        jdbc.update("""
                INSERT INTO importacao_rdo (
                    id, nome_arquivo, hash_arquivo, tamanho_bytes, status
                ) VALUES (?, 'legacy.xlsx', ?, 128, 'VALIDADA')
                """, importId, "a".repeat(64));
        jdbc.update("""
                UPDATE rdo
                SET fonte_criacao = 'IMPORTACAO_HISTORICA',
                    importacao_rdo_id = ?, fonte_arquivo = 'legacy.xlsx',
                    aba_origem = 'RDO', linha_origem = 42
                WHERE id = ?
                """, importId, rdoId);
    }

    private static RdoHistoricalImportServiceCommand historicalCommand(
            String importId,
            Fixture fixture,
            String executionId,
            String legacyItemId
    ) {
        return new RdoHistoricalImportServiceCommand(
                fixture.rdoId(), fixture.obraId(), importId, "legacy.xlsx",
                "RDO", 42, executionId, "Legacy asphalt service", legacyItemId,
                new BigDecimal("3.250"), null, null, null, "KM 2",
                "DIURNO", "historical source"
        );
    }

    private static void insertLegacyContractItem(String itemId, Fixture fixture) {
        jdbc.update("""
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total, vigencia_inicio, status
                ) VALUES (?, ?, 'LEGACY', ?, 'Legacy item', 'M2', 1, 0, 0, ?, 'ATIVO')
                """, itemId, fixture.obraId(), "LEGACY." + itemId,
                RDO_DATE.minusDays(1));
    }

    private static String insertPrice(
            String obraId,
            String serviceId,
            String actorId,
            String unit,
            String currency,
            String unitPrice,
            LocalDate validFrom,
            LocalDate validTo,
            int version,
            String supersedesId
    ) {
        String priceId = id();
        jdbc.update("""
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, vigencia_fim,
                    fonte, supersedes_id, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RDO_REVENUE_IT', ?, ?)
                """, priceId, obraId, serviceId, unit, currency, version,
                new BigDecimal(unitPrice), validFrom, validTo, supersedesId, actorId);
        return priceId;
    }

    private static void cancelPrice(
            String priceId,
            String obraId,
            String actorId,
            LocalDate cancellationDate
    ) {
        jdbc.update("""
                INSERT INTO service_price_version_cancellation (
                    id, price_version_id, obra_id, vigencia_cancelamento,
                    motivo, criado_por
                ) VALUES (?, ?, ?, ?, 'Test cancellation', ?)
                """, id(), priceId, obraId, cancellationDate, actorId);
    }

    private static RdoCreateRequest.ServicoExecutadoItem item(
            String executionId,
            String serviceId,
            String priceVersionId,
            String quantity,
            String unit,
            String status,
            boolean rework,
            boolean rejected,
            String observations
    ) throws Exception {
        return mapper.readValue("""
                {
                  "id":"%s",
                  "serviceId":"%s",
                  "priceVersionId":%s,
                  "servicoNome":"client supplied name must be ignored",
                  "quantidadeExecutada":%s,
                  "unidade":"%s",
                  "localizacao":"km 1+000",
                  "turno":"DIURNO",
                  "statusValidacao":"%s",
                  "retrabalho":%s,
                  "producaoRejeitada":%s,
                  "observacoes":%s
                }
                """.formatted(
                executionId,
                serviceId,
                priceVersionId == null ? "null" : "\"" + priceVersionId + "\"",
                quantity,
                unit,
                status,
                rework,
                rejected,
                observations == null ? "null" : "\"" + observations + "\""
        ), RdoCreateRequest.ServicoExecutadoItem.class);
    }

    private static BigDecimal quantity(String executionId) {
        return jdbc.queryForObject(
                "SELECT quantidade_executada FROM execucao_servico_rdo WHERE id = ?",
                BigDecimal.class, executionId
        );
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(
            String obraId,
            String actorId,
            String rdoId,
            String serviceId,
            String priceId
    ) {
    }
}

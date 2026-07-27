package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.ontology.graph.GraphProjectionCatchUpService;
import com.projeto.cortex.ontology.graph.GraphProjectionService;
import com.projeto.cortex.ontology.graph.OperationalGraphProjector;
import com.projeto.cortex.ontology.graph.PostgresqlCommittedOperationalEventReader;
import com.projeto.cortex.ontology.graph.PostgresqlOntologyGraphRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class PostgresqlServicePriceCatalogIT {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_service_catalog_it");

    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactions;

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
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void catalogIsGlobalAndIndependentFromContractItemsWhilePricesStayWorksiteScoped() {
        String obraA = insertWorksite("CAT-A");
        String obraB = insertWorksite("CAT-B");
        String actor = insertActor("Catalog Owner");
        ServicePriceCatalogService service = service(
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class)
        );
        ServiceCatalogEntry catalog = inTx(() -> service.createService(
                obraA, actor, serviceCommand("CAT-GLOBAL-1", "CAT.GLOBAL.1")
        ));
        ServicePriceVersion price = inTx(() -> service.createPrice(
                obraA, actor, catalog.id(),
                priceCommand("PRICE-A-1", "125.0000", LocalDate.of(2026, 1, 1), null)
        ));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM item_contratual WHERE obra_id IN (?, ?)",
                Integer.class, obraA, obraB
        )).isZero();
        assertThat(price.obraId()).isEqualTo(obraA);
        assertThat(repository().findPrice(obraB, price.id())).isEmpty();
        ServiceCatalogPage pageB = repository().list(obraB, "CAT.GLOBAL.1", null, 50);
        assertThat(pageB.items()).singleElement().satisfies(row -> {
            assertThat(row.service().id()).isEqualTo(catalog.id());
            assertThat(row.priceVersions()).isEmpty();
        });
        assertThat(new ObjectMapper().findAndRegisterModules()
                .valueToTree(pageB).toString())
                .doesNotContain("CAT-GLOBAL-1")
                .doesNotContain("createdBy")
                .doesNotContain("requestHash")
                .doesNotContainIgnoringCase("cpf")
                .doesNotContainIgnoringCase("email");
    }

    @Test
    void overlapIsRejectedAndSupersedePreservesImmutableHistory() {
        String obra = insertWorksite("HISTORY");
        String actor = insertActor("History Owner");
        ServicePriceCatalogService service = service(
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class)
        );
        ServiceCatalogEntry catalog = inTx(() -> service.createService(
                obra, actor, serviceCommand("CAT-HISTORY-1", "CAT.HISTORY.1")
        ));
        ServicePriceVersion first = inTx(() -> service.createPrice(
                obra, actor, catalog.id(),
                priceCommand(
                        "PRICE-HISTORY-1", "125.0000",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)
                )
        ));

        assertThatThrownBy(() -> inTx(() -> service.createPrice(
                obra, actor, catalog.id(),
                priceCommand(
                        "PRICE-HISTORY-OVERLAP", "126.0000",
                        LocalDate.of(2026, 6, 1), null
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_PRICE_VALIDITY_OVERLAP");

        ServicePriceVersion second = inTx(() -> service.supersedePrice(
                obra,
                actor,
                first.id(),
                new SupersedeServicePriceCommand(
                        "PRICE-HISTORY-2", new BigDecimal("130.0000"),
                        LocalDate.of(2026, 7, 1), null, "CONTRATO_MEDIDO"
                )
        ));
        ServicePriceVersion historical = repository().findPrice(obra, first.id())
                .orElseThrow();

        assertThat(second.version()).isEqualTo(2);
        assertThat(historical.unitPrice()).isEqualByComparingTo("125.0000");
        assertThat(historical.status()).isEqualTo("SUPERSEDED");
        assertThat(historical.effectiveValidTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        ServicePriceVersion cancelled = inTx(() -> service.cancelPrice(
                obra,
                actor,
                second.id(),
                new CancelServicePriceCommand(
                        "PRICE-HISTORY-CANCEL", LocalDate.of(2026, 8, 1),
                        "Revisão contratual"
                )
        ));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.effectiveValidTo()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE service_price_version SET valor_unitario = 1 WHERE id = ?",
                first.id()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("service_price_version_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM service_price_version WHERE id = ?", first.id()
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM service_price_version_cancellation WHERE price_version_id = ?",
                second.id()
        )).isInstanceOf(DataAccessException.class);
        ServiceCatalogPage coverage = service.list(
                obra, "CAT.HISTORY.1", null, 50
        );
        com.fasterxml.jackson.databind.JsonNode counts =
                new ObjectMapper().findAndRegisterModules().valueToTree(coverage);
        assertThat(counts.path("authorizedItemCount").asLong()).isOne();
        assertThat(counts.path("authorizedPriceVersionCount").asLong(-1L))
                .isEqualTo(2L);
        assertThat(counts.path("authorizedCancellationCount").asLong(-1L))
                .isOne();
        assertThat(counts.path("returnedPriceVersionCount").asInt(-1))
                .isEqualTo(2);
        assertThat(counts.path("returnedCancellationCount").asInt(-1))
                .isOne();
    }

    @Test
    void idempotencyBindsFullContentAndConcurrentOverlapCreatesOnlyOneVersion()
            throws Exception {
        String obra = insertWorksite("RACE");
        String actorA = insertActor("Race A");
        String actorB = insertActor("Race B");
        ServicePriceCatalogService service = service(
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class)
        );
        CreateServiceCommand create = serviceCommand("CAT-RACE-1", "CAT.RACE.1");
        ServiceCatalogEntry catalog = inTx(() -> service.createService(obra, actorA, create));
        assertThat(inTx(() -> service.createService(obra, actorA, create)).id())
                .isEqualTo(catalog.id());
        assertThatThrownBy(() -> inTx(() -> service.createService(
                obra, actorA,
                new CreateServiceCommand(
                        "CAT-RACE-1", "CAT.RACE.1", "Changed", "Serviço canônico"
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_CATALOG_IDEMPOTENCY_CONFLICT");

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> attempts = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            attempts.add(executor.submit(() -> attemptPrice(
                    start, service, obra, actorA, catalog.id(), "PRICE-RACE-A"
            )));
            attempts.add(executor.submit(() -> attemptPrice(
                    start, service, obra, actorB, catalog.id(), "PRICE-RACE-B"
            )));
            start.countDown();
            List<Object> results = List.of(attempts.get(0).get(), attempts.get(1).get());
            assertThat(results).filteredOn(ServicePriceVersion.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(ResponseStatusException.class::isInstance).hasSize(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version WHERE obra_id = ? AND service_id = ?",
                Integer.class, obra, catalog.id()
        )).isOne();

        ServiceCatalogEntry replayCatalog = inTx(() -> service.createService(
                obra,
                actorA,
                serviceCommand("CAT-RACE-REPLAY", "CAT.RACE.REPLAY")
        ));
        CountDownLatch replayStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> firstReplay = executor.submit(() -> attemptPrice(
                    replayStart, service, obra, actorA, replayCatalog.id(),
                    "PRICE-RACE-SAME-MUTATION"
            ));
            Future<Object> secondReplay = executor.submit(() -> attemptPrice(
                    replayStart, service, obra, actorA, replayCatalog.id(),
                    "PRICE-RACE-SAME-MUTATION"
            ));
            replayStart.countDown();
            Object firstResult = firstReplay.get();
            Object secondResult = secondReplay.get();
            assertThat(firstResult).isInstanceOf(ServicePriceVersion.class);
            assertThat(secondResult).isInstanceOf(ServicePriceVersion.class);
            assertThat(((ServicePriceVersion) firstResult).id())
                    .isEqualTo(((ServicePriceVersion) secondResult).id());
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version WHERE obra_id = ? AND service_id = ?",
                Integer.class, obra, replayCatalog.id()
        )).isOne();
    }

    @Test
    void missingAlfaWorksiteIsRejectedBeforeGlobalReadOrForeignKeyWrite() {
        String missingWorksite = id();
        String actor = insertActor("Missing Worksite ALFA");
        ServicePriceCatalogService service = service(
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class)
        );
        String code = "MISSING.WORKSITE." + missingWorksite.substring(0, 8);

        assertThatThrownBy(() -> service.list(
                missingWorksite, null, null, 50
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_CATALOG_WORKSITE_NOT_FOUND");
        assertThatThrownBy(() -> inTx(() -> service.createService(
                missingWorksite,
                actor,
                serviceCommand("MISSING-WORKSITE-CREATE", code)
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_CATALOG_WORKSITE_NOT_FOUND")
                .hasMessageNotContaining("foreign key")
                .hasMessageNotContaining("SQL");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM catalogo_servico WHERE codigo = ?",
                Integer.class,
                code
        )).isZero();
    }

    @Test
    void exactNumericMaximumPersistsAndFirstOverflowIsSafeBadRequest() {
        String obra = insertWorksite("PRICE-BOUNDARY");
        String actor = insertActor("Price Boundary Owner");
        ServicePriceCatalogService service = service(
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class)
        );
        ServiceCatalogEntry catalog = inTx(() -> service.createService(
                obra,
                actor,
                serviceCommand("CAT-PRICE-BOUNDARY", "CAT.PRICE.BOUNDARY")
        ));

        ServicePriceVersion maximum = inTx(() -> service.createPrice(
                obra,
                actor,
                catalog.id(),
                priceCommand(
                        "PRICE-EXACT-MAX", "99999999999999.9999",
                        LocalDate.of(2026, 1, 1), null
                )
        ));
        assertThat(maximum.unitPrice())
                .isEqualByComparingTo("99999999999999.9999");

        assertThatThrownBy(() -> inTx(() -> service.createPrice(
                obra,
                actor,
                catalog.id(),
                priceCommand(
                        "PRICE-FIRST-OVERFLOW", "100000000000000.0000",
                        LocalDate.of(2027, 1, 1), null
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value()
                ).isEqualTo(400))
                .hasMessageContaining("valorUnitario")
                .hasMessageNotContaining("numeric field overflow")
                .hasMessageNotContaining("SQL");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM service_catalog_mutation
                WHERE client_mutation_id = 'PRICE-FIRST-OVERFLOW'
                """,
                Integer.class
        )).isZero();
    }

    @Test
    void cancelledPriceCannotBeSupersededAndLeavesNoLosingWrite() {
        String obra = insertWorksite("CANCEL-THEN-SUPERSEDE");
        String actor = insertActor("Cancel Then Supersede Owner");
        ServicePriceCatalogService service = service(ontologyPublisher());
        ServiceCatalogEntry catalog = inTx(() -> service.createService(
                obra,
                actor,
                serviceCommand("CAT-CANCEL-SUP", "CAT.CANCEL.SUP")
        ));
        ServicePriceVersion first = inTx(() -> service.createPrice(
                obra,
                actor,
                catalog.id(),
                priceCommand(
                        "PRICE-CANCEL-SUP-1", "100.0000",
                        LocalDate.of(2026, 1, 1), null
                )
        ));
        inTx(() -> service.cancelPrice(
                obra,
                actor,
                first.id(),
                new CancelServicePriceCommand(
                        "PRICE-CANCEL-SUP-CANCEL", LocalDate.of(2026, 2, 1),
                        "Revisão contratual"
                )
        ));

        assertThatThrownBy(() -> inTx(() -> service.supersedePrice(
                obra,
                actor,
                first.id(),
                new SupersedeServicePriceCommand(
                        "PRICE-CANCEL-SUP-LOSER", new BigDecimal("110.0000"),
                        LocalDate.of(2026, 3, 1), null, "CONTRATO_MEDIDO"
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value()
                ).isEqualTo(409))
                .hasMessageContaining("SERVICE_PRICE_ALREADY_TERMINATED")
                .hasMessageNotContaining("constraint")
                .hasMessageNotContaining("SQL");
        assertNoMutationArtifacts("PRICE-CANCEL-SUP-LOSER");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version WHERE supersedes_id = ?",
                Integer.class,
                first.id()
        )).isZero();
    }

    @Test
    void predecessorAcceptsOnlyOneSuccessorEvenWhenIntervalsDoNotOverlap() {
        String obra = insertWorksite("DOUBLE-SUPERSEDE");
        String actor = insertActor("Double Supersede Owner");
        ServicePriceCatalogService service = service(ontologyPublisher());
        ServiceCatalogEntry catalog = inTx(() -> service.createService(
                obra,
                actor,
                serviceCommand("CAT-DOUBLE-SUP", "CAT.DOUBLE.SUP")
        ));
        ServicePriceVersion first = inTx(() -> service.createPrice(
                obra,
                actor,
                catalog.id(),
                priceCommand(
                        "PRICE-DOUBLE-SUP-1", "100.0000",
                        LocalDate.of(2026, 1, 1), null
                )
        ));
        inTx(() -> service.supersedePrice(
                obra,
                actor,
                first.id(),
                new SupersedeServicePriceCommand(
                        "PRICE-DOUBLE-SUP-WINNER", new BigDecimal("110.0000"),
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                        "CONTRATO_MEDIDO"
                )
        ));

        assertThatThrownBy(() -> inTx(() -> service.supersedePrice(
                obra,
                actor,
                first.id(),
                new SupersedeServicePriceCommand(
                        "PRICE-DOUBLE-SUP-LOSER", new BigDecimal("120.0000"),
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                        "CONTRATO_MEDIDO"
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value()
                ).isEqualTo(409))
                .hasMessageContaining("SERVICE_PRICE_ALREADY_TERMINATED")
                .hasMessageNotContaining("constraint")
                .hasMessageNotContaining("SQL");
        assertNoMutationArtifacts("PRICE-DOUBLE-SUP-LOSER");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version WHERE supersedes_id = ?",
                Integer.class,
                first.id()
        )).isOne();
    }

    @Test
    void concurrentCancelAndSupersedeHaveOneWinnerAndOneSafeConflict()
            throws Exception {
        String obra = insertWorksite("TERMINAL-RACE");
        String actorCancel = insertActor("Terminal Race Cancel");
        String actorSupersede = insertActor("Terminal Race Supersede");
        ServicePriceCatalogService service = service(ontologyPublisher());
        ServiceCatalogEntry catalog = inTx(() -> service.createService(
                obra,
                actorCancel,
                serviceCommand("CAT-TERMINAL-RACE", "CAT.TERMINAL.RACE")
        ));
        ServicePriceVersion first = inTx(() -> service.createPrice(
                obra,
                actorCancel,
                catalog.id(),
                priceCommand(
                        "PRICE-TERMINAL-RACE-1", "100.0000",
                        LocalDate.of(2026, 1, 1), null
                )
        ));
        CountDownLatch start = new CountDownLatch(1);

        Object cancelResult;
        Object supersedeResult;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> cancel = executor.submit(() -> {
                start.await();
                try {
                    return inTx(() -> service.cancelPrice(
                            obra,
                            actorCancel,
                            first.id(),
                            new CancelServicePriceCommand(
                                    "PRICE-TERMINAL-RACE-CANCEL",
                                    LocalDate.of(2026, 2, 1),
                                    "Revisão contratual"
                            )
                    ));
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            Future<Object> supersede = executor.submit(() -> {
                start.await();
                try {
                    return inTx(() -> service.supersedePrice(
                            obra,
                            actorSupersede,
                            first.id(),
                            new SupersedeServicePriceCommand(
                                    "PRICE-TERMINAL-RACE-SUPERSEDE",
                                    new BigDecimal("110.0000"),
                                    LocalDate.of(2026, 3, 1), null,
                                    "CONTRATO_MEDIDO"
                            )
                    ));
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            start.countDown();
            cancelResult = cancel.get();
            supersedeResult = supersede.get();
        }

        List<Object> results = List.of(cancelResult, supersedeResult);
        assertThat(results).filteredOn(ServicePriceVersion.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ResponseStatusException.class::isInstance)
                .singleElement()
                .satisfies(result -> {
                    ResponseStatusException conflict =
                            (ResponseStatusException) result;
                    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                    assertThat(conflict.getReason()).isIn(
                            "SERVICE_PRICE_ALREADY_TERMINATED",
                            "SERVICE_PRICE_CANCELLATION_INVALID"
                    );
                });
        assertThat(jdbc.queryForObject(
                """
                SELECT
                    (SELECT count(*) FROM service_price_version
                     WHERE supersedes_id = ?) +
                    (SELECT count(*) FROM service_price_version_cancellation
                     WHERE price_version_id = ?)
                """,
                Integer.class,
                first.id(),
                first.id()
        )).isOne();
        String losingMutation = cancelResult instanceof ResponseStatusException
                ? "PRICE-TERMINAL-RACE-CANCEL"
                : "PRICE-TERMINAL-RACE-SUPERSEDE";
        assertNoMutationArtifacts(losingMutation);
    }

    @Test
    void paginationInvalidatesWhenAnOlderBlockedMutationCommitsAfterFirstPage()
            throws Exception {
        String obra = insertWorksite("SNAPSHOT");
        String actorOld = insertActor("Snapshot Old Mutation");
        String actorNew = insertActor("Snapshot New Mutation");
        ServicePriceCatalogService currentService = service(
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class)
        );
        ServicePriceCatalogService olderClockService = new ServicePriceCatalogService(
                repository(),
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class),
                Clock.fixed(NOW.minusSeconds(600), ZoneOffset.UTC)
        );
        ServiceCatalogEntry firstCatalog = inTx(() -> currentService.createService(
                obra,
                actorOld,
                serviceCommand("CAT-SNAPSHOT-1", "CAT.SNAPSHOT.001")
        ));
        ServiceCatalogEntry secondCatalog = inTx(() -> currentService.createService(
                obra,
                actorNew,
                serviceCommand("CAT-SNAPSHOT-2", "CAT.SNAPSHOT.002")
        ));

        Connection blocker = dataSource.getConnection();
        blocker.setAutoCommit(false);
        try (PreparedStatement lock = blocker.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
        )) {
            lock.setString(
                    1,
                    "service_catalog_mutation:" + actorOld + ":PRICE-SNAPSHOT-OLD"
            );
            lock.execute();
        }

        ServiceCatalogPage firstPage;
        Future<ServicePriceVersion> oldMutation;
        var executor = Executors.newSingleThreadExecutor();
        try {
            oldMutation = executor.submit(() -> inTx(() ->
                    olderClockService.createPrice(
                            obra,
                            actorOld,
                            firstCatalog.id(),
                            priceCommand(
                                    "PRICE-SNAPSHOT-OLD", "10.0000",
                                    LocalDate.of(2026, 1, 1), null
                            )
                    )
            ));
            awaitWaitingAdvisoryLock();
            inTx(() -> currentService.createPrice(
                    obra,
                    actorNew,
                    secondCatalog.id(),
                    priceCommand(
                            "PRICE-SNAPSHOT-NEW", "20.0000",
                            LocalDate.of(2026, 1, 1), null
                    )
            ));
            firstPage = currentService.list(obra, "CAT.SNAPSHOT", null, 1);
            blocker.commit();
            blocker.close();
            oldMutation.get();
        } finally {
            if (!blocker.isClosed()) {
                blocker.rollback();
                blocker.close();
            }
            executor.shutdownNow();
        }

        com.fasterxml.jackson.databind.JsonNode coverage =
                new ObjectMapper().findAndRegisterModules().valueToTree(firstPage);
        assertThat(coverage.path("authorizedItemCount").asLong()).isEqualTo(2L);
        assertThat(coverage.path("authorizedPriceVersionCount").asLong(-1L))
                .isEqualTo(1L);
        assertThat(coverage.path("authorizedCancellationCount").asLong(-1L))
                .isZero();
        assertThat(coverage.path("returnedPriceVersionCount").asInt(-1))
                .isZero();
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThatThrownBy(() -> currentService.list(
                obra, "CAT.SNAPSHOT", firstPage.nextCursor(), 1
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value()
                ).isEqualTo(409))
                .hasMessageContaining("SERVICE_CATALOG_SNAPSHOT_STALE");

        ServiceCatalogPage restarted = currentService.list(
                obra, "CAT.SNAPSHOT", null, 100
        );
        com.fasterxml.jackson.databind.JsonNode restartedCoverage =
                new ObjectMapper().findAndRegisterModules().valueToTree(restarted);
        assertThat(restartedCoverage.path("authorizedPriceVersionCount").asLong(-1L))
                .isEqualTo(2L);
        assertThat(restarted.coverage()).isEqualTo("COMPLETE");
    }

    @Test
    void paginatesAndSearchesMoreThanThreeHundredServicesWithExactCoverage() {
        String obra = insertWorksite("PAGING");
        String actor = insertActor("Paging Owner");
        ServicePriceCatalogService service = service(
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class)
        );
        for (int index = 0; index < 305; index++) {
            int current = index;
            inTx(() -> service.createService(
                    obra,
                    actor,
                    serviceCommand(
                            "CAT-PAGE-" + current,
                            "PAGING.SERVICE.%03d".formatted(current)
                    )
            ));
        }

        String cursor = null;
        List<String> ids = new ArrayList<>();
        int pages = 0;
        do {
            ServiceCatalogPage page = service.list(
                    obra, "PAGING.SERVICE", cursor, 37
            );
            pages++;
            ids.addAll(page.items().stream()
                    .map(row -> row.service().id())
                    .toList());
            assertThat(page.authorizedItemCount()).isEqualTo(305);
            assertThat(page.highWaterMark()).isNotNegative();
            assertThat(page.returnedItemCount()).isEqualTo(page.items().size());
            assertThat(page.coverage()).isEqualTo(
                    page.nextCursor() == null ? "COMPLETE" : "PARTIAL"
            );
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(pages).isGreaterThan(8);
        assertThat(ids).hasSize(305).doesNotHaveDuplicates();
    }

    @Test
    void ontologyPublicationIsExactlyOnceForIdempotentReplay() {
        String obra = insertWorksite("ONTOLOGY");
        String actor = insertActor("Ontology Owner");
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                org.mockito.Mockito.mock(ApplicationEventPublisher.class)
        );
        ServicePriceCatalogService service = service(
                new PostgresqlServiceCatalogOntologyPublisher(memory)
        );
        ServiceCatalogEntry catalog = inTx(() -> service.createService(
                obra, actor, serviceCommand("CAT-ONTOLOGY-1", "CAT.ONTOLOGY.1")
        ));
        CreateServicePriceCommand command = priceCommand(
                "PRICE-ONTOLOGY-1", "99.5000", LocalDate.of(2026, 1, 1), null
        );
        ServicePriceVersion first = inTx(() -> service.createPrice(
                obra, actor, catalog.id(), command
        ));
        ServicePriceVersion replay = inTx(() -> service.createPrice(
                obra, actor, catalog.id(), command
        ));
        CancelServicePriceCommand cancel = new CancelServicePriceCommand(
                "PRICE-ONTOLOGY-CANCEL", LocalDate.of(2026, 2, 1),
                "Revisão contratual"
        );
        ServicePriceVersion cancelled = inTx(() -> service.cancelPrice(
                obra, actor, first.id(), cancel
        ));
        ServicePriceVersion cancelReplay = inTx(() -> service.cancelPrice(
                obra, actor, first.id(), cancel
        ));
        ServicePriceVersion predecessor = inTx(() -> service.createPrice(
                obra,
                actor,
                catalog.id(),
                priceCommand(
                        "PRICE-ONTOLOGY-SUPERSEDED", "105.0000",
                        LocalDate.of(2026, 2, 1), null
                )
        ));
        SupersedeServicePriceCommand supersede = new SupersedeServicePriceCommand(
                "PRICE-ONTOLOGY-SUPERSESSION", new BigDecimal("110.0000"),
                LocalDate.of(2026, 4, 1), null, "CONTRATO_MEDIDO"
        );
        ServicePriceVersion replacement = inTx(() -> service.supersedePrice(
                obra, actor, predecessor.id(), supersede
        ));
        ServicePriceVersion supersedeReplay = inTx(() -> service.supersedePrice(
                obra, actor, predecessor.id(), supersede
        ));
        projectAvailableGraph();

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelReplay.id()).isEqualTo(cancelled.id());
        assertThat(supersedeReplay.id()).isEqualTo(replacement.id());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_evento_operacional
                WHERE tipo_evento = 'SERVICE_PRICE_VERSION_PUBLISHED'
                  AND entidade_id = ?
                """, Integer.class, first.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_relacao
                WHERE origem_tipo = 'SERVICE'
                  AND origem_id = ?
                  AND destino_tipo = 'SERVICE_PRICE_VERSION'
                  AND destino_id = ?
                  AND tipo_relacao = 'PRICED_BY'
                  AND ativa = TRUE
                """, Integer.class, catalog.id(), first.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_evento_operacional
                WHERE tipo_evento = 'SERVICE_PRICE_VERSION_CANCELLED'
                  AND entidade_id = ?
                """, Integer.class, first.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM cortex_objeto
                WHERE tipo_entidade = 'SERVICE_PRICE_VERSION'
                  AND entidade_id = ?
                """, String.class, first.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM cortex_objeto
                WHERE tipo_entidade = 'SERVICE_PRICE_VERSION'
                  AND entidade_id = ?
                """, String.class, predecessor.id())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM cortex_objeto
                WHERE tipo_entidade = 'SERVICE_PRICE_VERSION'
                  AND entidade_id = ?
                """, String.class, replacement.id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_relacao
                WHERE origem_tipo = 'SERVICE_PRICE_VERSION'
                  AND origem_id = ?
                  AND destino_tipo = 'SERVICE_PRICE_VERSION'
                  AND destino_id = ?
                  AND tipo_relacao = 'SUPERSEDES'
                  AND ativa = TRUE
                """, Integer.class, replacement.id(), predecessor.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_evento_operacional
                WHERE tipo_evento = 'SERVICE_PRICE_VERSION_SUPERSEDED'
                  AND entidade_id = ?
                  AND correlacao_id = 'PRICE-ONTOLOGY-SUPERSESSION'
                """, Integer.class, predecessor.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_evento_operacional
                WHERE tipo_evento = 'SERVICE_PRICE_VERSION_PUBLISHED'
                  AND entidade_id = ?
                  AND correlacao_id = 'PRICE-ONTOLOGY-SUPERSESSION'
                """, Integer.class, replacement.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM ontology_entities
                WHERE entity_type = 'SERVICE_PRICE_VERSION'
                  AND external_ref_id = ?
                """, String.class, predecessor.id())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM ontology_entities
                WHERE entity_type = 'SERVICE_PRICE_VERSION'
                  AND external_ref_id = ?
                """, String.class, replacement.id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM ontology_relations relation
                JOIN ontology_entities source
                  ON source.id = relation.source_entity_id
                JOIN ontology_entities target
                  ON target.id = relation.target_entity_id
                WHERE relation.relation_type = 'SUPERSEDES'
                  AND source.external_ref_id = ?
                  AND target.external_ref_id = ?
                """, Integer.class, replacement.id(), predecessor.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM ontology_relations relation
                JOIN ontology_entities source
                  ON source.id = relation.source_entity_id
                JOIN ontology_entities target
                  ON target.id = relation.target_entity_id
                WHERE relation.relation_type = 'PRICED_BY'
                  AND source.external_ref_id = ?
                  AND target.external_ref_id IN (?, ?)
                """, Integer.class, catalog.id(), predecessor.id(), replacement.id()))
                .isEqualTo(2);
    }

    private static Object attemptPrice(
            CountDownLatch start,
            ServicePriceCatalogService service,
            String obra,
            String actor,
            String catalogId,
            String mutationId
    ) throws InterruptedException {
        start.await();
        try {
            return inTx(() -> service.createPrice(
                    obra, actor, catalogId,
                    priceCommand(
                            mutationId, "10.0000", LocalDate.of(2026, 1, 1), null
                    )
            ));
        } catch (ResponseStatusException conflict) {
            return conflict;
        }
    }

    private static void assertNoMutationArtifacts(String clientMutationId) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_catalog_mutation WHERE client_mutation_id = ?",
                Integer.class,
                clientMutationId
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cortex_evento_operacional WHERE correlacao_id = ?",
                Integer.class,
                clientMutationId
        )).isZero();
    }

    private static void awaitWaitingAdvisoryLock() throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM pg_locks
                    WHERE locktype = 'advisory' AND granted = FALSE
                    """,
                    Integer.class
            );
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Blocked catalog mutation did not reach advisory lock.");
    }

    private static ServiceCatalogOntologyPublisher ontologyPublisher() {
        return new PostgresqlServiceCatalogOntologyPublisher(
                new CortexOperationalMemoryService(
                        jdbc,
                        new ObjectMapper().findAndRegisterModules(),
                        org.mockito.Mockito.mock(ApplicationEventPublisher.class)
                )
        );
    }

    private static void projectAvailableGraph() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PostgresqlOntologyGraphRepository graphRepository =
                new PostgresqlOntologyGraphRepository(
                        jdbc, objectMapper, transactionManager
                );
        GraphProjectionCatchUpService catchUp = new GraphProjectionCatchUpService(
                new PostgresqlCommittedOperationalEventReader(jdbc, objectMapper),
                new GraphProjectionService(
                        new OperationalGraphProjector(), graphRepository
                ),
                graphRepository
        );
        catchUp.projectAvailable(10_000);
    }

    private static ServicePriceCatalogService service(
            ServiceCatalogOntologyPublisher publisher
    ) {
        return new ServicePriceCatalogService(
                repository(),
                publisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static PostgresqlServicePriceCatalogRepository repository() {
        return new PostgresqlServicePriceCatalogRepository(jdbc);
    }

    private static CreateServiceCommand serviceCommand(
            String mutationId,
            String code
    ) {
        return new CreateServiceCommand(
                mutationId, code, "Serviço " + code, "Serviço canônico"
        );
    }

    private static CreateServicePriceCommand priceCommand(
            String mutationId,
            String amount,
            LocalDate validFrom,
            LocalDate validTo
    ) {
        return new CreateServicePriceCommand(
                mutationId,
                "M2",
                "BRL",
                new BigDecimal(amount),
                new BigDecimal("800.000"),
                validFrom,
                validTo,
                "CONTRATO_MEDIDO"
        );
    }

    private static String insertWorksite(String suffix) {
        String id = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                id, "CTR-" + suffix + "-" + id.substring(0, 8), "Obra " + suffix
        );
        return id;
    }

    private static String insertActor(String name) {
        String id = id();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, ?, 'ALFA')
                """, id, id, name);
        return id;
    }

    private static <T> T inTx(java.util.concurrent.Callable<T> callback) {
        return transactions.execute(status -> {
            try {
                return callback.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}

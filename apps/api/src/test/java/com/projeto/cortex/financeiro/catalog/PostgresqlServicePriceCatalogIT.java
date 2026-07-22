package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
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
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
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
                "UPDATE service_price_version SET unit_price = 1 WHERE id = ?", first.id()
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM service_price_version WHERE id = ?", first.id()
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM service_price_version_cancellation WHERE price_version_id = ?",
                second.id()
        )).isInstanceOf(DataAccessException.class);
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
            assertThat(page.highWaterMark()).isNotNull();
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

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelReplay.id()).isEqualTo(cancelled.id());
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

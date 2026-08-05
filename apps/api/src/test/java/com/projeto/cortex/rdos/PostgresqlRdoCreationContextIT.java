package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlRdoCreationContextIT {

    private static final LocalDate SELECTED_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_rdo_context_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static DriverManagerDataSource dataSource;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void upgradeDeV48EV49AplicadasPreservaChecksumELiberaV50() {
        try (PostgreSQLContainer<?> upgradeDatabase = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("cortex_rdo_v48_upgrade_it")) {
            upgradeDatabase.start();
            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword()
                    )
                    .locations("classpath:db/migration-postgresql")
                    .target("47")
                    .load()
                    .migrate();
            JdbcTemplate upgradeJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    upgradeDatabase.getJdbcUrl(),
                    upgradeDatabase.getUsername(),
                    upgradeDatabase.getPassword()
            ));

            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword()
                    )
                    .locations("classpath:db/migration-postgresql")
                    .target("49")
                    .load()
                    .migrate();

            assertThat(upgradeJdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('48', '49')",
                    Integer.class
            )).isEqualTo(2);
            assertThat(upgradeJdbc.queryForObject(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'rdo' AND column_name = 'creation_owner_id'
                    """,
                    Integer.class
            )).isZero();
            String obraId = id();
            upgradeJdbc.update(
                    "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra upgrade')",
                    obraId,
                    "CTR-" + obraId
            );
            upgradeJdbc.update(
                    "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, 'RDO-0041', ?)",
                    id(), obraId, SELECTED_DATE.minusDays(2)
            );
            upgradeJdbc.update(
                    "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, 'RDO-0041', ?)",
                    id(), obraId, SELECTED_DATE.minusDays(1)
            );

            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword()
                    )
                    .locations("classpath:db/migration-postgresql")
                    .target("50")
                    .load()
                    .migrate();

            // Exercise the context service against the current canonical schema.
            // The assertions below still prove V48/V49/V50 upgrade preservation.
            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword()
                    )
                    .locations("classpath:db/migration-postgresql")
                    .target("57")
                    .load()
                    .migrate();

            assertThat(upgradeJdbc.queryForObject(
                    "SELECT count(*) FROM rdo WHERE obra_id = ? AND numero_rdo = 'RDO-0041'",
                    Integer.class,
                    obraId
            )).isEqualTo(2);
            assertThat(new RdoContextService(upgradeJdbc)
                    .buscarContexto(obraId, SELECTED_DATE)
                    .nextNumberSuggestion()).isEqualTo("RDO-0042");
            assertThat(upgradeJdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '48'",
                    Integer.class
            )).isOne();
            assertThat(upgradeJdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '50'",
                    Integer.class
            )).isOne();
            assertThat(upgradeJdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM flyway_schema_history
                    WHERE success AND version IN ('56', '57')
                    """,
                    Integer.class
            )).isEqualTo(2);
            assertThat(upgradeJdbc.queryForObject(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'rdo' AND column_name = 'creation_owner_id'
                    """,
                    Integer.class
            )).isOne();
        }
    }

    @Test
    void retornaRdoAnteriorDeterministicoEquipeCompletaECandidatosDaObraSemPii() {
        String obraA = id();
        String obraB = id();
        inserirObra(obraA, "A");
        inserirObra(obraB, "B");
        String vinculada = inserirColaborador("Vinculada", "vinculada@fixture.invalid", "***.111.***-**");
        String historica = inserirColaborador("Histórica", "historica@fixture.invalid", "***.222.***-**");
        String outraObra = inserirColaborador("Outra obra", "outra@fixture.invalid", "***.333.***-**");
        vincular(vinculada, obraA, "APONTADOR", "ATIVO");
        vincular(historica, obraA, "OPERACIONAL", "REVOGADO");
        vincular(outraObra, obraB, "OPERACIONAL", "ATIVO");

        LocalDateTime empate = LocalDateTime.of(2026, 7, 21, 18, 0);
        inserirRdo("00000000-0000-4000-8000-000000000110", obraA, "RDO-0040",
                SELECTED_DATE.minusDays(1), "RASCUNHO", empate, empate);
        String anterior = "00000000-0000-4000-8000-000000000120";
        inserirRdo(anterior, obraA, "RDO-0041",
                SELECTED_DATE.minusDays(1), "ENVIADO", empate, empate);
        inserirRdo("00000000-0000-4000-8000-000000000999", obraA, "RDO-0999",
                SELECTED_DATE, "CANCELADO", empate.plusHours(1), empate.plusHours(1));
        inserirRdo(id(), obraB, "RDO-9000",
                SELECTED_DATE.minusDays(1), "ENVIADO", empate.plusHours(2), empate.plusHours(2));
        String assetA = inserirAsset("EQ-A");
        String assetB = inserirAsset("EQ-B");
        inserirEquipamento(id(), anterior, assetA);
        String rdoB = id();
        inserirRdo(rdoB, obraB, "RDO-9001",
                SELECTED_DATE.minusDays(2), "ENVIADO", empate, empate);
        inserirEquipamento(id(), rdoB, assetB);
        inserirMaoObra("item-vinculado-" + id().substring(0, 12), anterior, vinculada, "Apontadora");
        inserirMaoObra("item-historico-" + id().substring(0, 12), anterior, historica, "Operador");

        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obraA, SELECTED_DATE);

        assertThat(response.previousRdo()).isNotNull();
        assertThat(response.previousRdo().id()).isEqualTo(anterior);
        assertThat(response.previousRdo().numeroRdo()).isEqualTo("RDO-0041");
        assertThat(response.previousWorkforce())
                .extracting(RdoContextResponse.PreviousWorkforceItem::collaboratorId)
                .containsExactlyInAnyOrder(vinculada, historica);
        assertThat(response.previousWorkforce())
                .filteredOn(item -> item.collaboratorId().equals(vinculada))
                .singleElement().extracting(RdoContextResponse.PreviousWorkforceItem::availability)
                .isEqualTo("AVAILABLE");
        assertThat(response.previousWorkforce())
                .filteredOn(item -> item.collaboratorId().equals(historica))
                .singleElement().extracting(RdoContextResponse.PreviousWorkforceItem::availability)
                .isEqualTo("UNAVAILABLE");
        assertThat(response.colaboradores())
                .extracting(RdoContextResponse.ColaboradorContexto::id)
                .containsExactly(vinculada);
        assertThat(response.equipamentos())
                .extracting(RdoContextResponse.EquipamentoContexto::id)
                .containsExactly(assetA);
        assertThat(response.nextNumberSuggestion()).isEqualTo("RDO-1000");
        assertThat(response.provenance().sourceVersion()).isPositive();
        assertThat(response.provenance().receiptVersion()).isPositive();
        assertThat(response.provenance().receiptVersion())
                .isLessThanOrEqualTo(9_007_199_254_740_991L);
        assertThat(response.provenance().previousRdoId()).isEqualTo(anterior);
    }

    @Test
    void usaRdoCausalMaisRecenteDoMesmoDiaSemSerReordenadoPorEdicao() {
        String obraId = id();
        inserirObra(obraId, "MESMO-DIA");
        String anterior = id();
        String primeiroDoDia = id();
        String segundoDoDia = id();
        String itemAnterior = id();
        String itemSegundo = id();
        LocalDateTime base = LocalDateTime.of(2026, 7, 22, 8, 0);

        inserirRdo(
                anterior, obraId, "RDO-0040", SELECTED_DATE.minusDays(1),
                "ENVIADO", base.minusDays(1), base.minusDays(1)
        );
        inserirRdo(
                primeiroDoDia, obraId, "RDO-0041", SELECTED_DATE,
                "RASCUNHO", base, base.plusHours(8)
        );
        inserirRdo(
                segundoDoDia, obraId, "RDO-0042", SELECTED_DATE,
                "RASCUNHO", base.plusMinutes(1), base.plusMinutes(1)
        );
        jdbc.update(
                "UPDATE rdo SET numero_sequencial = ? WHERE id = ?",
                41L,
                primeiroDoDia
        );
        jdbc.update(
                "UPDATE rdo SET numero_sequencial = ? WHERE id = ?",
                42L,
                segundoDoDia
        );
        inserirMaoObra(itemAnterior, anterior, null, "Equipe anterior");
        inserirMaoObra(itemSegundo, segundoDoDia, null, "Equipe atual");

        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE);

        assertThat(response.previousRdo())
                .isNotNull()
                .extracting(RdoContextResponse.PreviousRdo::id)
                .isEqualTo(segundoDoDia);
        assertThat(response.previousWorkforce())
                .singleElement()
                .extracting(
                        RdoContextResponse.PreviousWorkforceItem::sourceItemId,
                        RdoContextResponse.PreviousWorkforceItem::roleSnapshot
                )
                .containsExactly(itemSegundo, "Equipe atual");
    }

    @Test
    void snapshotNaoTruncaColecoesEDeclaraCoberturaFreshnessECatalogosAusentes() {
        String obraId = id();
        inserirObra(obraId, "COBERTURA");
        for (int index = 0; index < 301; index += 1) {
            String collaborator = inserirColaborador("Pessoa %03d".formatted(index), null, null);
            vincular(collaborator, obraId, "OPERACIONAL", "ATIVO");
            String asset = inserirAsset("EQ-%03d".formatted(index));
            tornarAssetElegivel(asset, obraId);
        }

        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE);

        assertThat(response.colaboradores()).hasSize(301);
        assertThat(response.equipamentos()).hasSize(301);
        assertThat(response.coverage().colaboradores())
                .extracting(
                        RdoContextResponse.CoverageSection::status,
                        RdoContextResponse.CoverageSection::total,
                        RdoContextResponse.CoverageSection::returned,
                        RdoContextResponse.CoverageSection::complete
                ).containsExactly("COMPLETE", 301L, 301L, true);
        assertThat(response.coverage().equipamentos().status()).isEqualTo("COMPLETE");
        assertThat(response.coverage().serviceCatalog().status()).isEqualTo("COMPLETE");
        assertThat(response.coverage().priceCatalog().status()).isEqualTo("COMPLETE");
        assertThat(response.freshness().catalogRevision()).isNotNegative();
        assertThat(response.freshness().status()).isEqualTo("FRESH");
        assertThat(response.freshness().staleAfter())
                .isAfter(response.freshness().generatedAt());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_creation_context_snapshot WHERE receipt_version = ?",
                Integer.class,
                response.provenance().receiptVersion()
        )).isOne();
    }

    @Test
    void reutilizaReceiptCanonicoParaContextoIdenticoEPreservaCriacaoRdo() throws Exception {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-CANONICO");
        String collaborator = inserirColaborador("Apontador canônico", null, null);
        vincular(collaborator, obraId, "APONTADOR", "ATIVO");
        RdoContextService contextService = new RdoContextService(jdbc);

        RdoContextResponse first = contextService.buscarContexto(obraId, SELECTED_DATE);
        RdoContextResponse replay = contextService.buscarContexto(obraId, SELECTED_DATE);

        assertThat(replay.provenance().receiptVersion())
                .isEqualTo(first.provenance().receiptVersion());
        assertThat(replay.provenance().generatedAt())
                .isEqualTo(replay.freshness().generatedAt());
        assertThat(replay.freshness().staleAfter())
                .isAfter(replay.freshness().generatedAt());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_creation_context_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isOne();

        RdoCreateRequest base = request(
                id(), obraId, id(), 1L, null, collaborator, id(), null
        );
        ObjectNode requestJson = mapper.valueToTree(base);
        requestJson.put(
                "creationContextVersion",
                replay.provenance().receiptVersion()
        );
        RdoCreateRequest request = mapper.treeToValue(requestJson, RdoCreateRequest.class);

        RdoResponse created = transactions.execute(
                status -> service(collaborator).criarRascunho(request)
        );
        assertThat(created.creationContextVersion())
                .isEqualTo(replay.provenance().receiptVersion());
        assertThat(jdbc.queryForObject(
                "SELECT creation_context_version FROM rdo WHERE id = ?",
                Long.class,
                created.id()
        )).isEqualTo(replay.provenance().receiptVersion());

        RdoContextResponse afterCreation =
                contextService.buscarContexto(obraId, SELECTED_DATE);
        assertThat(afterCreation.provenance().sourceVersion())
                .isGreaterThan(replay.provenance().sourceVersion());
        assertThat(afterCreation.provenance().receiptVersion())
                .isNotEqualTo(replay.provenance().receiptVersion());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_creation_context_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isEqualTo(2);
    }

    @Test
    void leituraRepetidaNaoAtualizaTuplaNemCriaReceiptAdicional() {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-SEM-UPDATE");
        RdoContextService contextService = new RdoContextService(jdbc);

        RdoContextResponse first = contextService.buscarContexto(obraId, SELECTED_DATE);
        ContextSnapshotTuple before = contextSnapshotTuple(
                first.provenance().receiptVersion()
        );

        RdoContextResponse replay = contextService.buscarContexto(obraId, SELECTED_DATE);
        ContextSnapshotTuple after = contextSnapshotTuple(
                replay.provenance().receiptVersion()
        );

        assertThat(replay.provenance().receiptVersion())
                .isEqualTo(first.provenance().receiptVersion());
        assertThat(after)
                .as("o replay deve ser somente leitura, sem nova versão MVCC/WAL")
                .isEqualTo(before);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ? AND canonical_key IS NOT NULL
                """,
                Integer.class,
                obraId
        )).isOne();
    }

    @Test
    void replayDepoisDoEpochDeFreshnessCriaNovoReceiptSemReescreverAnterior() {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-STALE");
        Instant generatedAt = Instant.parse("2026-07-23T12:00:00Z");
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        RdoContextService initialService = new RdoContextService(
                jdbc,
                mapper,
                transactionManager,
                Clock.fixed(generatedAt, ZoneOffset.UTC)
        );

        RdoContextResponse initial =
                initialService.buscarContexto(obraId, SELECTED_DATE);
        ContextSnapshotTuple before = contextSnapshotTuple(
                initial.provenance().receiptVersion()
        );
        RdoContextService replayService = new RdoContextService(
                jdbc,
                mapper,
                transactionManager,
                Clock.fixed(
                        generatedAt.plusSeconds(16 * 60L),
                        ZoneOffset.UTC
                )
        );

        RdoContextResponse replay =
                replayService.buscarContexto(obraId, SELECTED_DATE);

        assertThat(initial.freshness().status()).isEqualTo("FRESH");
        assertThat(replay.freshness().status()).isEqualTo("FRESH");
        assertThat(replay.provenance().receiptVersion())
                .isNotEqualTo(initial.provenance().receiptVersion());
        assertThat(contextSnapshotTuple(initial.provenance().receiptVersion()))
                .as("um novo epoch não deve reescrever o receipt anterior")
                .isEqualTo(before);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ? AND canonical_key IS NOT NULL
                """,
                Integer.class,
                obraId
        )).isEqualTo(2);
    }

    @Test
    void recusaDatasForaDoDominioOperacionalSemPersistirReceipts() {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-DATA");
        RdoContextService contextService = new RdoContextService(jdbc);
        LocalDate today = LocalDate.now();

        assertThatThrownBy(() ->
                contextService.buscarContexto(obraId, today.minusDays(91))
        ).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        ).hasMessageContaining("data");
        assertThatThrownBy(() ->
                contextService.buscarContexto(obraId, today.plusDays(32))
        ).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        ).hasMessageContaining("data");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_creation_context_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isZero();
    }

    @Test
    void quotaPorAtorNaoPermiteLockoutMesmoComMaisDe512ReceiptsDeOutrosAtores() {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-QUOTA");
        String abusiveActor = id();
        String secondAbusiveActor = id();
        String admittedActor = id();
        inserirReceiptsCanonicos(obraId, 256, false, abusiveActor);
        inserirReceiptsCanonicos(obraId, 256, false, secondAbusiveActor);

        assertThatThrownBy(() ->
                new RdoContextService(jdbc).buscarContexto(
                        obraId,
                        SELECTED_DATE,
                        abusiveActor
                )
        ).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        ).hasMessageContaining("ator");
        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE, admittedActor);

        assertThat(response.provenance().receiptVersion()).isPositive();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ? AND canonical_key IS NOT NULL
                """,
                Integer.class,
                obraId
        )).isEqualTo(513);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ?
                  AND issued_by_id = ?
                  AND canonical_key IS NOT NULL
                """,
                Integer.class,
                obraId,
                admittedActor
        )).isOne();
    }

    @Test
    void retencaoPriorizaExpiradosDoAtorMesmoAposMaisDe512LegadosAntigos() {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-ACTOR-RETENTION");
        String actor = id();
        inserirReceiptsCanonicos(obraId, 513, true);
        inserirReceiptsCanonicos(obraId, 256, true, actor);
        jdbc.update(
                """
                UPDATE rdo_creation_context_snapshot
                SET generated_at = now() - interval '200 days',
                    stale_after = now() - interval '199 days'
                WHERE obra_id = ?
                  AND issued_by_id IS NULL
                """,
                obraId
        );

        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE, actor);

        assertThat(response.provenance().receiptVersion()).isPositive();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ?
                  AND issued_by_id = ?
                  AND canonical_key IS NOT NULL
                """,
                Integer.class,
                obraId,
                actor
        )).isOne();
    }

    @Test
    void retencaoRemoveReceiptsExpiradosNaoReferenciadosAntesDaQuota() {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-RETENCAO");
        inserirReceiptsCanonicos(obraId, 512, true);

        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE);

        assertThat(response.provenance().receiptVersion()).isPositive();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ? AND canonical_key IS NOT NULL
                """,
                Integer.class,
                obraId
        )).isBetween(1, 512);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ?
                  AND canonical_key IS NOT NULL
                  AND generated_at < now() - interval '90 days'
                """,
                Integer.class,
                obraId
        )).isZero();
    }

    @Test
    void fastPathConcorrenteComRetencaoNuncaRetornaReceiptRemovido()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-FAST-RACE");
        Instant currentInstant = Instant.parse("2026-07-23T16:00:00Z");
        LocalDate raceDate = LocalDate.ofInstant(
                currentInstant,
                ZoneOffset.UTC
        ).minusDays(89);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        RdoContextResponse oldContext = new RdoContextService(
                jdbc,
                mapper,
                transactionManager,
                Clock.fixed(
                        currentInstant.minus(Duration.ofDays(100)),
                        ZoneOffset.UTC
                )
        ).buscarContexto(obraId, raceDate);
        inserirReceiptsCanonicos(obraId, 511, true);
        CountDownLatch firstLookupCompleted = new CountDownLatch(1);
        CountDownLatch allowFirstLookupToReturn = new CountDownLatch(1);
        JdbcTemplate coordinatedJdbc = new PausingCanonicalLookupJdbcTemplate(
                dataSource,
                firstLookupCompleted,
                allowFirstLookupToReturn
        );
        Clock currentClock = Clock.fixed(currentInstant, ZoneOffset.UTC);
        RdoContextService fastPathService = new RdoContextService(
                coordinatedJdbc,
                mapper,
                transactionManager,
                currentClock
        );
        RdoContextService cleanupService = new RdoContextService(
                jdbc,
                mapper,
                transactionManager,
                currentClock
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RdoContextResponse> fastPath = executor.submit(() ->
                    fastPathService.buscarContexto(obraId, raceDate)
            );
            await(firstLookupCompleted);
            Future<RdoContextResponse> cleanup = executor.submit(() ->
                    cleanupService.buscarContexto(
                            obraId,
                            raceDate.plusDays(1)
                    )
            );
            RdoContextResponse cleanupResponse =
                    cleanup.get(10, TimeUnit.SECONDS);
            allowFirstLookupToReturn.countDown();
            RdoContextResponse fastPathResponse =
                    fastPath.get(10, TimeUnit.SECONDS);

            assertThat(fastPathResponse.provenance().receiptVersion())
                    .isNotEqualTo(oldContext.provenance().receiptVersion());
            assertThat(fastPathResponse.freshness().status()).isEqualTo("FRESH");
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM rdo_creation_context_snapshot
                    WHERE receipt_version IN (?, ?)
                    """,
                    Integer.class,
                    fastPathResponse.provenance().receiptVersion(),
                    cleanupResponse.provenance().receiptVersion()
            )).isEqualTo(2);
        } finally {
            allowFirstLookupToReturn.countDown();
        }
    }

    @Test
    void criacaoEretencaoConcorrentesNuncaDeixamProvenienciaPendente()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-CREATE-RACE");
        String collaborator = inserirColaborador(
                "Apontador corrida de retenção",
                null,
                null
        );
        vincular(collaborator, obraId, "APONTADOR", "ATIVO");
        Instant currentInstant = Instant.parse("2026-07-23T16:00:00Z");
        LocalDate raceDate = LocalDate.ofInstant(
                currentInstant,
                ZoneOffset.UTC
        ).minusDays(89);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        RdoCreateRequest base = request(
                id(), obraId, id(), 1L, null, collaborator, id(), null
        );
        RdoContextResponse oldContext = new RdoContextService(
                jdbc,
                mapper,
                transactionManager,
                Clock.fixed(
                        currentInstant.minus(Duration.ofDays(100)),
                        ZoneOffset.UTC
                )
        ).buscarContexto(obraId, raceDate);
        ObjectNode withOldReceipt = mapper.valueToTree(base);
        withOldReceipt.put("dataRdo", raceDate.toString());
        withOldReceipt.put(
                "creationContextVersion",
                oldContext.provenance().receiptVersion()
        );
        RdoCreateRequest request = mapper.treeToValue(
                withOldReceipt,
                RdoCreateRequest.class
        );
        inserirReceiptsCanonicos(obraId, 510, true);
        CountDownLatch provenanceLocked = new CountDownLatch(1);
        CountDownLatch allowCreationToContinue = new CountDownLatch(1);
        JdbcTemplate coordinatedJdbc = new PausingProvenanceLockJdbcTemplate(
                dataSource,
                provenanceLocked,
                allowCreationToContinue
        );
        RdoService coordinatedService = service(
                collaborator,
                mock(RdoMemoryPublisher.class),
                coordinatedJdbc
        );
        TransactionTemplate coordinatedTransactions =
                new TransactionTemplate(transactionManager);
        RdoContextService cleanupService = new RdoContextService(
                jdbc,
                mapper,
                transactionManager,
                Clock.fixed(currentInstant, ZoneOffset.UTC)
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RdoResponse> creation = executor.submit(() ->
                    coordinatedTransactions.execute(
                            status -> coordinatedService.criarRascunho(request)
                    )
            );
            await(provenanceLocked);
            Future<RdoContextResponse> cleanup = executor.submit(() ->
                    cleanupService.buscarContexto(
                            obraId,
                            raceDate.plusDays(1)
                    )
            );
            awaitWaitingAdvisoryLock(cleanup);
            allowCreationToContinue.countDown();

            RdoResponse created = creation.get(10, TimeUnit.SECONDS);
            cleanup.get(10, TimeUnit.SECONDS);
            assertThat(created.creationContextVersion())
                    .isEqualTo(oldContext.provenance().receiptVersion());
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM rdo_creation_context_snapshot snapshot
                    JOIN rdo
                      ON rdo.creation_context_version =
                         snapshot.receipt_version
                    WHERE rdo.id = ?
                    """,
                    Integer.class,
                    created.id()
            )).isOne();
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM rdo
                    LEFT JOIN rdo_creation_context_snapshot snapshot
                      ON snapshot.receipt_version =
                         rdo.creation_context_version
                    WHERE rdo.id = ?
                      AND rdo.creation_context_version IS NOT NULL
                      AND snapshot.receipt_version IS NULL
                    """,
                    Integer.class,
                    created.id()
            )).isZero();
        } finally {
            allowCreationToContinue.countDown();
        }
    }

    @Test
    void chamadasConcorrentesReutilizamUmUnicoReceiptCanonico() throws Exception {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-CONCORRENTE");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<RdoContextResponse>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < 16; index += 1) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return new RdoContextService(jdbc)
                            .buscarContexto(obraId, SELECTED_DATE);
                }));
            }
            start.countDown();

            List<Long> receipts = new java.util.ArrayList<>();
            for (Future<RdoContextResponse> future : futures) {
                receipts.add(future.get().provenance().receiptVersion());
            }
            assertThat(receipts).containsOnly(receipts.get(0));
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_creation_context_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isOne();
    }

    @Test
    void criaNovoReceiptQuandoPayloadDoCatalogoMudaMesmoComSourceVersionIgual() {
        String obraId = id();
        inserirObra(obraId, "RECEIPT-PAYLOAD");
        String collaborator = inserirColaborador("Gestor de catálogo", null, null);
        vincular(collaborator, obraId, "GESTOR_FINANCEIRO", "ATIVO");
        RdoContextService contextService = new RdoContextService(jdbc);

        RdoContextResponse before = contextService.buscarContexto(obraId, SELECTED_DATE);
        jdbc.update(
                """
                INSERT INTO catalogo_servico (
                    id, codigo, nome, obra_autorizadora_id, criado_por
                ) VALUES (?, ?, ?, ?, ?)
                """,
                id(), "SV-" + id().substring(0, 8).toUpperCase(),
                "Serviço adicionado", obraId, collaborator
        );
        RdoContextResponse changed = contextService.buscarContexto(obraId, SELECTED_DATE);

        assertThat(changed.provenance().sourceVersion())
                .isEqualTo(before.provenance().sourceVersion());
        assertThat(changed.freshness().catalogRevision())
                .isGreaterThan(before.freshness().catalogRevision());
        assertThat(changed.provenance().receiptVersion())
                .isNotEqualTo(before.provenance().receiptVersion());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_creation_context_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isEqualTo(2);
    }

    @Test
    void recusaReceiptForjadoMasAceitaSnapshotDuravelQueFicouStale() throws Exception {
        String obraId = id();
        inserirObra(obraId, "RECEIPT");
        String collaborator = inserirColaborador("Equipe", null, null);
        vincular(collaborator, obraId, "APONTADOR", "ATIVO");
        RdoContextResponse snapshot = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE);
        RdoCreateRequest base = request(
                id(), obraId, id(), 1L, null, collaborator, id(), null
        );
        ObjectNode staleJson = mapper.valueToTree(base);
        staleJson.put("creationContextVersion", snapshot.provenance().receiptVersion());
        RdoCreateRequest stale = mapper.treeToValue(staleJson, RdoCreateRequest.class);

        jdbc.update("UPDATE obra SET atualizado_em = now() + interval '1 second' WHERE id = ?", obraId);
        RdoResponse created = transactions.execute(
                status -> service(collaborator).criarRascunho(stale)
        );
        assertThat(created.id()).isEqualTo(stale.id());

        ObjectNode forgedJson = mapper.valueToTree(request(
                id(), obraId, id(), 1L, null, collaborator, id(), null
        ));
        forgedJson.put("creationContextVersion", Long.MAX_VALUE - 17);
        RdoCreateRequest forged = mapper.treeToValue(forgedJson, RdoCreateRequest.class);
        assertThatThrownBy(() -> transactions.execute(
                status -> service(collaborator).criarRascunho(forged)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("contexto")
                .hasMessageContaining("válido");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ?",
                Integer.class,
                forged.id()
        )).isZero();
    }

    @Test
    void criaConcorrentementeComNumerosAutoritativosSequenciaisEIdsDeEquipeEstaveis()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "CONCORRENTE");
        String colaborador = inserirColaborador("Equipe", null, null);
        vincular(colaborador, obraId, "APONTADOR", "ATIVO");
        inserirRdo(id(), obraId, "RDO-0041", SELECTED_DATE.minusDays(1),
                "ENVIADO", LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1));
        String equipeA = id();
        String equipeB = id();
        RdoService service = service(colaborador);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RdoResponse> first = executor.submit(createTask(
                    start, service, request(id(), obraId, id(), 1L, null,
                            colaborador, equipeA, null)
            ));
            Future<RdoResponse> second = executor.submit(createTask(
                    start, service, request(id(), obraId, id(), 1L, null,
                            colaborador, equipeB, null)
            ));
            start.countDown();

            List<RdoResponse> created = List.of(first.get(), second.get());
            assertThat(created).extracting(RdoResponse::numeroRdo)
                    .containsExactlyInAnyOrder("RDO-0042", "RDO-0043");
        }

        assertThat(jdbc.queryForList(
                "SELECT id FROM rdo_mao_obra WHERE id IN (?, ?) ORDER BY id",
                String.class, equipeA, equipeB
        )).containsExactlyInAnyOrder(equipeA, equipeB);
    }

    @Test
    void criaMaoDeObraManualEHerdavelSemConcederAcessoAObra()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "MAO-OBRA-MANUAL");
        String owner = inserirColaborador("Responsável do RDO", null, null);
        vincular(owner, obraId, "APONTADOR", "ATIVO");
        int collaboratorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM colaborador",
                Integer.class
        );
        int linksBefore = jdbc.queryForObject(
                "SELECT count(*) FROM vinculo_colaborador_obra",
                Integer.class
        );
        String rdoId = id();
        String workforceItemId = id();
        ObjectNode manualJson = mapper.valueToTree(request(
                rdoId,
                obraId,
                id(),
                1L,
                null,
                owner,
                workforceItemId,
                null
        ));
        manualJson.putNull("apontadorColaboradorId");
        manualJson.put("apontadorRdo", "  Mestre Nominal  ");
        ObjectNode manualItem = (ObjectNode) manualJson
                .withArray("maoObra")
                .get(0);
        manualItem.putNull("colaboradorId");
        manualItem.put("nomeColaborador", "  Maria Servente  ");
        manualItem.put("cargo", "Servente");
        RdoCreateRequest manualRequest = mapper.treeToValue(
                manualJson,
                RdoCreateRequest.class
        );

        transactions.execute(
                status -> service(owner).criarRascunho(manualRequest)
        );

        assertThat(jdbc.queryForMap(
                """
                SELECT colaborador_id, nome_colaborador, cargo
                FROM rdo_mao_obra
                WHERE id = ?
                """,
                workforceItemId
        )).satisfies(row -> {
            assertThat(row.get("colaborador_id")).isNull();
            assertThat(row.get("nome_colaborador"))
                    .isEqualTo("Maria Servente");
            assertThat(row.get("cargo")).isEqualTo("Servente");
        });
        assertThat(jdbc.queryForObject(
                "SELECT apontador_rdo FROM rdo WHERE id = ?",
                String.class,
                rdoId
        )).isEqualTo("Mestre Nominal");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM colaborador",
                Integer.class
        )).isEqualTo(collaboratorsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vinculo_colaborador_obra",
                Integer.class
        )).isEqualTo(linksBefore);

        RdoContextResponse nextContext = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE.plusDays(1));
        assertThat(nextContext.previousWorkforce())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.sourceItemId())
                            .isEqualTo(workforceItemId);
                    assertThat(item.collaboratorId()).isNull();
                    assertThat(item.nameSnapshot())
                            .isEqualTo("Maria Servente");
                    assertThat(item.availability())
                            .isEqualTo("AVAILABLE");
                });
    }

    @Test
    void recusaMaoDeObraManualSemNome() throws Exception {
        String obraId = id();
        inserirObra(obraId, "MAO-OBRA-MANUAL-SEM-NOME");
        String owner = inserirColaborador("Responsável do RDO", null, null);
        vincular(owner, obraId, "APONTADOR", "ATIVO");
        String rdoId = id();
        ObjectNode requestJson = mapper.valueToTree(request(
                rdoId,
                obraId,
                id(),
                1L,
                null,
                owner,
                id(),
                null
        ));
        requestJson.putNull("apontadorColaboradorId");
        ObjectNode manualItem = (ObjectNode) requestJson
                .withArray("maoObra")
                .get(0);
        manualItem.putNull("colaboradorId");
        manualItem.put("nomeColaborador", "   ");
        RdoCreateRequest request = mapper.treeToValue(
                requestJson,
                RdoCreateRequest.class
        );

        assertThatThrownBy(() -> transactions.execute(
                status -> service(owner).criarRascunho(request)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("maoObra.nomeColaborador")
                .hasMessageContaining("obrigatório");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ?",
                Integer.class,
                rdoId
        )).isZero();

        manualItem.put(
                "nomeColaborador",
                "  " + "M".repeat(255) + "  "
        );
        RdoCreateRequest boundaryRequest = mapper.treeToValue(
                requestJson,
                RdoCreateRequest.class
        );
        transactions.execute(
                status -> service(owner).criarRascunho(boundaryRequest)
        );
        assertThat(jdbc.queryForObject(
                """
                SELECT char_length(nome_colaborador)
                FROM rdo_mao_obra
                WHERE rdo_id = ?
                """,
                Integer.class,
                rdoId
        )).isEqualTo(255);
    }

    @Test
    void recusaNomeDeMaoDeObraManualAcimaDoLimiteDoBanco()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "MAO-OBRA-MANUAL-NOME-LONGO");
        String owner = inserirColaborador("Responsável do RDO", null, null);
        vincular(owner, obraId, "APONTADOR", "ATIVO");
        String rdoId = id();
        ObjectNode requestJson = mapper.valueToTree(request(
                rdoId,
                obraId,
                id(),
                1L,
                null,
                owner,
                id(),
                null
        ));
        requestJson.putNull("apontadorColaboradorId");
        ObjectNode manualItem = (ObjectNode) requestJson
                .withArray("maoObra")
                .get(0);
        manualItem.putNull("colaboradorId");
        manualItem.put("nomeColaborador", "M".repeat(256));
        RdoCreateRequest request = mapper.treeToValue(
                requestJson,
                RdoCreateRequest.class
        );

        assertThatThrownBy(() -> transactions.execute(
                status -> service(owner).criarRascunho(request)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("maoObra.nomeColaborador")
                .hasMessageContaining("255 caracteres");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ?",
                Integer.class,
                rdoId
        )).isZero();
    }

    @Test
    void herdaMaoDeObraManualDoMesmoDiaEPermiteEditarOuExcluirNoNovoRdo()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "MAO-OBRA-MANUAL-HERDADA");
        String owner = inserirColaborador("Responsável do RDO", null, null);
        vincular(owner, obraId, "APONTADOR", "ATIVO");
        String previousRdoId = id();
        inserirRdo(
                previousRdoId,
                obraId,
                "RDO-0001",
                SELECTED_DATE,
                "ENVIADO",
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().minusDays(1)
        );
        String sourceItemId = id();
        inserirMaoObra(sourceItemId, previousRdoId, null, "Servente");
        String currentRdoId = id();
        String currentItemId = id();
        ObjectNode inheritedJson = mapper.valueToTree(request(
                currentRdoId,
                obraId,
                id(),
                1L,
                previousRdoId,
                owner,
                currentItemId,
                sourceItemId
        ));
        inheritedJson.putNull("apontadorColaboradorId");
        ObjectNode inheritedItem = (ObjectNode) inheritedJson
                .withArray("maoObra")
                .get(0);
        inheritedItem.putNull("colaboradorId");
        inheritedItem.put("nomeColaborador", "Snapshot");
        RdoCreateRequest inheritedRequest = mapper.treeToValue(
                inheritedJson,
                RdoCreateRequest.class
        );

        transactions.execute(
                status -> service(owner).criarRascunho(inheritedRequest)
        );

        ObjectNode editedJson = inheritedJson.deepCopy();
        ((ObjectNode) editedJson.withArray("maoObra").get(0))
                .put(
                        "nomeColaborador",
                        "  " + "L".repeat(255) + "  "
                );
        RdoCreateRequest editedRequest = mapper.treeToValue(
                editedJson,
                RdoCreateRequest.class
        );
        RdoDraftUpdateService updateService = draftService();
        transactions.execute(
                status -> updateService.atualizarRascunho(
                        currentRdoId,
                        editedRequest
                )
        );
        assertThat(jdbc.queryForMap(
                """
                SELECT colaborador_id, nome_colaborador, origem_item_id
                FROM rdo_mao_obra
                WHERE id = ?
                """,
                currentItemId
        )).satisfies(row -> {
            assertThat(row.get("colaborador_id")).isNull();
            assertThat(row.get("nome_colaborador"))
                    .isEqualTo("L".repeat(255));
            assertThat(row.get("origem_item_id")).isEqualTo(sourceItemId);
        });

        ObjectNode withoutWorkforceJson = editedJson.deepCopy();
        withoutWorkforceJson.putArray("maoObra");
        RdoCreateRequest withoutWorkforce = mapper.treeToValue(
                withoutWorkforceJson,
                RdoCreateRequest.class
        );
        transactions.execute(
                status -> updateService.atualizarRascunho(
                        currentRdoId,
                        withoutWorkforce
                )
        );
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_mao_obra WHERE rdo_id = ?",
                Integer.class,
                currentRdoId
        )).isZero();
    }

    @Test
    void replayDaMesmaMutacaoNaoCriaOutroRdoNemConsomeOutroNumero() throws Exception {
        String obraId = id();
        inserirObra(obraId, "IDEMPOTENTE");
        String colaborador = inserirColaborador("Equipe", null, null);
        vincular(colaborador, obraId, "OPERACIONAL", "ATIVO");
        String rdoId = id();
        String mutationId = id();
        RdoCreateRequest request = request(
                rdoId, obraId, mutationId, 1L, null, colaborador, id(), null
        );
        RdoCreateRequest newMutation = request(
                id(),
                obraId,
                id(),
                1L,
                null,
                colaborador,
                id(),
                null
        );
        RdoService service = service(colaborador);

        RdoResponse first = transactions.execute(status -> service.criarRascunho(request));
        jdbc.update(
                "UPDATE obra SET arquivado_em = CURRENT_TIMESTAMP WHERE id = ?",
                obraId
        );
        RdoResponse replay = transactions.execute(status -> service.criarRascunho(request));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.numeroRdo()).isEqualTo(first.numeroRdo());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE client_mutation_id = ?",
                Integer.class, mutationId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT next_value FROM rdo_number_sequence WHERE obra_id = ?",
                Long.class, obraId
        )).isEqualTo(2L);
        assertThat(jdbc.queryForMap(
                "SELECT creation_owner_id, creation_payload_hash FROM rdo WHERE id = ?",
                rdoId
        )).satisfies(row -> {
            assertThat(row.get("creation_owner_id")).isEqualTo(colaborador);
            assertThat(row.get("creation_payload_hash").toString())
                    .matches("[0-9a-f]{64}");
        });

        assertThatThrownBy(() -> transactions.execute(
                status -> service.criarRascunho(newMutation)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("arquivada");

        ObjectNode mismatchedJson = mapper.valueToTree(request);
        ((ObjectNode) mismatchedJson.withArray("maoObra").get(0))
                .put("observacoes", "conteúdo divergente");
        RdoCreateRequest mismatchedReplay = mapper.treeToValue(
                mismatchedJson,
                RdoCreateRequest.class
        );
        assertThatThrownBy(() -> transactions.execute(
                status -> service.criarRascunho(mismatchedReplay)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outro conteúdo");

        String alternateApontador = inserirColaborador("Apontador alternativo", null, null);
        vincular(alternateApontador, obraId, "APONTADOR", "ATIVO");
        ObjectNode alternateApontadorJson = mapper.valueToTree(request);
        alternateApontadorJson.put("apontadorColaboradorId", alternateApontador);
        RdoCreateRequest alternateApontadorReplay = mapper.treeToValue(
                alternateApontadorJson,
                RdoCreateRequest.class
        );
        assertThatThrownBy(() -> transactions.execute(
                status -> service.criarRascunho(alternateApontadorReplay)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outro conteúdo");

        String otherOwner = inserirColaborador("Outro dono", null, null);
        assertThatThrownBy(() -> transactions.execute(
                status -> service(otherOwner).criarRascunho(request)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outro conteúdo");
    }

    @Test
    void replayConcorrenteDaMesmaMutacaoCriaExatamenteUmRdo() throws Exception {
        String obraId = id();
        inserirObra(obraId, "REPLAY-CONCORRENTE");
        String colaborador = inserirColaborador("Equipe", null, null);
        vincular(colaborador, obraId, "OPERACIONAL", "ATIVO");
        String mutationId = id();
        RdoCreateRequest request = request(
                id(), obraId, mutationId, 1L, null, colaborador, id(), null
        );
        RdoService service = service(colaborador);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RdoResponse> first = executor.submit(createTask(start, service, request));
            Future<RdoResponse> replay = executor.submit(createTask(start, service, request));
            start.countDown();

            assertThat(replay.get().numeroRdo()).isEqualTo(first.get().numeroRdo());
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE client_mutation_id = ?",
                Integer.class,
                mutationId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT next_value FROM rdo_number_sequence WHERE obra_id = ?",
                Long.class,
                obraId
        )).isEqualTo(2L);
    }

    @Test
    void mesmaMutacaoDeOwnersDiferentesCriaDoisRdosDeterministicamente()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "OWNERS-DIFERENTES");
        String ownerA = inserirColaborador("Owner A", null, null);
        String ownerB = inserirColaborador("Owner B", null, null);
        vincular(ownerA, obraId, "OPERACIONAL", "ATIVO");
        vincular(ownerB, obraId, "OPERACIONAL", "ATIVO");
        String mutationId = id();
        String rdoA = id();
        String rdoB = id();
        RdoCreateRequest requestA = request(
                rdoA, obraId, mutationId, 1L, null, ownerA, id(), null
        );
        RdoCreateRequest requestB = request(
                rdoB, obraId, mutationId, 1L, null, ownerB, id(), null
        );
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RdoResponse> first = executor.submit(
                    createTask(start, service(ownerA), requestA)
            );
            Future<RdoResponse> second = executor.submit(
                    createTask(start, service(ownerB), requestB)
            );
            start.countDown();

            assertThat(List.of(first.get().id(), second.get().id()))
                    .containsExactlyInAnyOrder(rdoA, rdoB);
        }

        assertThat(jdbc.queryForList(
                """
                SELECT creation_owner_id
                FROM rdo
                WHERE client_mutation_id = ?
                ORDER BY creation_owner_id
                """,
                String.class,
                mutationId
        )).containsExactlyInAnyOrder(ownerA, ownerB);
    }

    @Test
    void replayConcorrenteDivergenteRecusaUmPayloadSemConsumirOutroNumero()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "REPLAY-DIVERGENTE");
        String colaborador = inserirColaborador("Equipe", null, null);
        vincular(colaborador, obraId, "APONTADOR", "ATIVO");
        String mutationId = id();
        RdoCreateRequest original = request(
                id(), obraId, mutationId, 1L, null, colaborador, id(), null
        );
        ObjectNode divergentJson = mapper.valueToTree(original);
        divergentJson.put("observacoes", "payload concorrente diferente");
        RdoCreateRequest divergent = mapper.treeToValue(
                divergentJson,
                RdoCreateRequest.class
        );
        RdoService service = service(colaborador);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RdoResponse> first = executor.submit(createTask(start, service, original));
            Future<RdoResponse> second = executor.submit(createTask(start, service, divergent));
            start.countDown();

            int successes = 0;
            int conflicts = 0;
            for (Future<RdoResponse> future : List.of(first, second)) {
                try {
                    future.get();
                    successes += 1;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                            .isInstanceOf(ResponseStatusException.class)
                            .hasMessageContaining("outro conteúdo");
                    conflicts += 1;
                }
            }
            assertThat(successes).isOne();
            assertThat(conflicts).isOne();
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE client_mutation_id = ?",
                Integer.class,
                mutationId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT next_value FROM rdo_number_sequence WHERE obra_id = ?",
                Long.class,
                obraId
        )).isEqualTo(2L);
    }

    @Test
    void rejeitaOrigemApontadorEColaboradorForaDoEscopoDaObra() throws Exception {
        String obraA = id();
        String obraB = id();
        inserirObra(obraA, "ESCOPO-A");
        inserirObra(obraB, "ESCOPO-B");
        String colaboradorA = inserirColaborador("A", null, null);
        String colaboradorB = inserirColaborador("B", null, null);
        vincular(colaboradorA, obraA, "OPERACIONAL", "ATIVO");
        vincular(colaboradorB, obraB, "APONTADOR", "ATIVO");
        String previousA = id();
        inserirRdo(previousA, obraA, "RDO-0001", SELECTED_DATE.minusDays(1),
                "ENVIADO", LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1));
        String previousB = id();
        inserirRdo(previousB, obraB, "RDO-0001", SELECTED_DATE.minusDays(1),
                "ENVIADO", LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1));
        String origemB = id();
        inserirMaoObra(origemB, previousB, colaboradorB, "Apontador");
        RdoService service = service(colaboradorA);

        RdoCreateRequest crossScope = request(
                id(), obraA, id(), 1L, previousB, colaboradorB, id(), origemB
        );

        assertThatThrownBy(() -> transactions.execute(
                status -> service.criarRascunho(crossScope)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("obra");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ?",
                Integer.class, crossScope.id()
        )).isZero();

        RdoCreateRequest foreignOrigin = request(
                id(), obraA, id(), 1L, previousA, colaboradorA, id(), origemB
        );
        assertThatThrownBy(() -> transactions.execute(
                status -> service.criarRascunho(foreignOrigin)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("item de origem");

        RdoCreateRequest validWorker = request(
                id(), obraA, id(), 1L, previousA, colaboradorA, id(), null
        );
        var forgedApontadorJson = mapper.valueToTree(validWorker);
        ((com.fasterxml.jackson.databind.node.ObjectNode) forgedApontadorJson)
                .put("apontadorColaboradorId", colaboradorB);
        RdoCreateRequest foreignApontador = mapper.treeToValue(
                forgedApontadorJson,
                RdoCreateRequest.class
        );
        assertThatThrownBy(() -> transactions.execute(
                status -> service.criarRascunho(foreignApontador)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("apontador");
    }

    @Test
    void elegibilidadeDeAssetEAutoritativaNoSnapshotECriacaoSemPublicarOntologia()
            throws Exception {
        String obraA = id();
        String obraB = id();
        inserirObra(obraA, "ASSET-A");
        inserirObra(obraB, "ASSET-B");
        String colaborador = inserirColaborador("Apontador", null, null);
        vincular(colaborador, obraA, "APONTADOR", "ATIVO");
        String assetA = inserirAsset("EQ-AUTORITATIVO");
        tornarAssetElegivel(assetA, obraA);

        assertThat(new RdoContextService(jdbc).buscarContexto(obraA, SELECTED_DATE)
                .equipamentos())
                .extracting(RdoContextResponse.EquipamentoContexto::id)
                .containsExactly(assetA);
        assertThat(new RdoContextService(jdbc).buscarContexto(obraB, SELECTED_DATE)
                .equipamentos()).isEmpty();

        ObjectNode crossScopeJson = mapper.valueToTree(request(
                id(), obraA, id(), 1L, null, colaborador, id(), null
        ));
        crossScopeJson.putArray("equipamentos").addObject()
                .put("assetId", inserirAsset("EQ-SEM-VINCULO"))
                .put("descricao", "Equipamento de outra obra");
        RdoCreateRequest crossScope = mapper.treeToValue(
                crossScopeJson,
                RdoCreateRequest.class
        );
        RdoMemoryPublisher memory = mock(RdoMemoryPublisher.class);

        assertThatThrownBy(() -> transactions.execute(
                status -> service(colaborador, memory).criarRascunho(crossScope)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("equipamento")
                .hasMessageContaining("obra");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ?",
                Integer.class,
                crossScope.id()
        )).isZero();
        verify(memory, never()).registrarRdoCriado(any(), any(), any(), any(), any());
    }

    @Test
    void bancoRecusaAssetDeOutraObraMesmoSemPassarPeloServico() {
        String obraA = id();
        String obraB = id();
        inserirObra(obraA, "DB-ASSET-A");
        inserirObra(obraB, "DB-ASSET-B");
        String assetA = inserirAsset("EQ-DB-A");
        tornarAssetElegivel(assetA, obraA);
        String rdoB = id();
        inserirRdo(rdoB, obraB, "RDO-0001", SELECTED_DATE, "RASCUNHO",
                LocalDateTime.now(), LocalDateTime.now());

        assertThatThrownBy(() -> inserirEquipamentoSemElegibilidade(id(), rdoB, assetA))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_equipamento WHERE rdo_id = ?",
                Integer.class,
                rdoB
        )).isZero();
    }

    @Test
    void atualizacaoRecusaTrocaParaAssetForaDaObraSemAlterarFilhosOuOntologia()
            throws Exception {
        String obraA = id();
        String obraB = id();
        inserirObra(obraA, "UPDATE-ASSET-A");
        inserirObra(obraB, "UPDATE-ASSET-B");
        String colaborador = inserirColaborador("Equipe", null, null);
        vincular(colaborador, obraA, "APONTADOR", "ATIVO");
        String assetA = inserirAsset("EQ-UPDATE-A");
        String assetB = inserirAsset("EQ-UPDATE-B");
        tornarAssetElegivel(assetA, obraA);
        tornarAssetElegivel(assetB, obraB);
        ObjectNode createJson = mapper.valueToTree(request(
                id(), obraA, id(), 1L, null, colaborador, id(), null
        ));
        createJson.putArray("equipamentos").addObject()
                .put("assetId", assetA)
                .put("descricao", "Equipamento elegível");
        RdoCreateRequest create = mapper.treeToValue(createJson, RdoCreateRequest.class);
        transactions.execute(status -> service(colaborador).criarRascunho(create));

        ObjectNode updateJson = createJson.deepCopy();
        ((ObjectNode) updateJson.withArray("equipamentos").get(0))
                .put("assetId", assetB);
        RdoCreateRequest invalidUpdate = mapper.treeToValue(
                updateJson,
                RdoCreateRequest.class
        );
        RdoMemoryPublisher memory = mock(RdoMemoryPublisher.class);

        assertThatThrownBy(() -> transactions.execute(
                status -> draftService(memory).atualizarRascunho(create.id(), invalidUpdate)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("equipamento")
                .hasMessageContaining("obra");
        assertThat(jdbc.queryForList(
                "SELECT asset_id FROM rdo_equipamento WHERE rdo_id = ?",
                String.class,
                create.id()
        )).containsExactly(assetA);
        verify(memory, never()).registrarRdoEditado(
                any(), any(), any(), any(), any(), anyLong(), anyLong(), any()
        );
    }

    @Test
    void atualizacaoPreservaIdsDaEquipeEImpedeTrocaDeObra() throws Exception {
        String obraA = id();
        String obraB = id();
        inserirObra(obraA, "UPDATE-A");
        inserirObra(obraB, "UPDATE-B");
        String colaborador = inserirColaborador("Equipe", null, null);
        vincular(colaborador, obraA, "OPERACIONAL", "ATIVO");
        vincular(colaborador, obraB, "OPERACIONAL", "ATIVO");
        String rdoId = id();
        String itemId = id();
        RdoCreateRequest create = request(
                rdoId, obraA, id(), 1L, null, colaborador, itemId, null
        );
        transactions.execute(status -> service(colaborador).criarRascunho(create));
        RdoDraftUpdateService updateService = draftService();

        transactions.execute(status -> updateService.atualizarRascunho(rdoId, create));

        assertThat(jdbc.queryForList(
                "SELECT id FROM rdo_mao_obra WHERE rdo_id = ?",
                String.class, rdoId
        )).containsExactly(itemId);

        RdoCreateRequest crossWorksite = request(
                rdoId, obraB, id(), 1L, null, colaborador, itemId, null
        );
        assertThatThrownBy(() -> transactions.execute(
                status -> updateService.atualizarRascunho(rdoId, crossWorksite)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("obra");
        assertThat(jdbc.queryForObject(
                "SELECT obra_id FROM rdo WHERE id = ?", String.class, rdoId
        )).isEqualTo(obraA);
    }

    @Test
    void remocaoDeEquipeEEquipamentoInativaObjetosEArestasSemRecriarNoReplay()
            throws Exception {
        String obraId = id();
        inserirObra(obraId, "HISTORICO-FILHOS");
        String colaborador = inserirColaborador("Equipe", null, null);
        vincular(colaborador, obraId, "OPERACIONAL", "ATIVO");
        String assetId = inserirAsset("EQ-HISTORICO");
        tornarAssetElegivel(assetId, obraId);
        String workforceId = id();
        String equipmentId = id();
        ObjectNode createJson = mapper.valueToTree(request(
                id(), obraId, id(), 1L, null, colaborador, workforceId, null
        ));
        createJson.putArray("equipamentos").addObject()
                .put("id", equipmentId)
                .put("assetId", assetId)
                .put("descricao", "Equipamento rastreável");
        RdoCreateRequest create = mapper.treeToValue(createJson, RdoCreateRequest.class);
        RdoMemoryPublisher memoryPublisher = realMemoryPublisher();

        transactions.execute(
                status -> service(colaborador, memoryPublisher).criarRascunho(create)
        );
        assertThat(jdbc.queryForList(
                "SELECT id FROM rdo_equipamento WHERE rdo_id = ?",
                String.class,
                create.id()
        )).containsExactly(equipmentId);

        ObjectNode removalJson = createJson.deepCopy();
        removalJson.putArray("maoObra");
        removalJson.putArray("equipamentos");
        removalJson.putNull("apontadorColaboradorId");
        RdoCreateRequest removal = mapper.treeToValue(removalJson, RdoCreateRequest.class);
        RdoDraftUpdateService updateService = draftService(memoryPublisher);
        transactions.execute(
                status -> updateService.atualizarRascunho(create.id(), removal)
        );

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_mao_obra WHERE rdo_id = ?",
                Integer.class,
                create.id()
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_equipamento WHERE rdo_id = ?",
                Integer.class,
                create.id()
        )).isZero();
        assertThat(jdbc.queryForList(
                """
                SELECT tipo_entidade, entidade_id, status
                FROM cortex_objeto
                WHERE (tipo_entidade = 'RDO_MAO_OBRA' AND entidade_id = ?)
                   OR (tipo_entidade = 'RDO_EQUIPAMENTO' AND entidade_id = ?)
                ORDER BY tipo_entidade
                """,
                workforceId,
                equipmentId
        )).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("INATIVO"));
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_relacao
                WHERE ativa = TRUE
                  AND ((origem_id IN (?, ?)) OR (destino_id IN (?, ?)))
                """,
                Integer.class,
                workforceId,
                equipmentId,
                workforceId,
                equipmentId
        )).isZero();
        Integer historicalRelations = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_relacao
                WHERE ativa = FALSE
                  AND ((origem_id IN (?, ?)) OR (destino_id IN (?, ?)))
                """,
                Integer.class,
                workforceId,
                equipmentId,
                workforceId,
                equipmentId
        );
        assertThat(historicalRelations).isPositive();
        Map<String, Object> beforeReplay = jdbc.queryForMap(
                """
                SELECT entidade_id, versao_linha
                FROM cortex_objeto
                WHERE tipo_entidade = 'RDO_EQUIPAMENTO' AND entidade_id = ?
                """,
                equipmentId
        );

        transactions.execute(
                status -> updateService.atualizarRascunho(create.id(), removal)
        );

        assertThat(jdbc.queryForMap(
                """
                SELECT entidade_id, versao_linha
                FROM cortex_objeto
                WHERE tipo_entidade = 'RDO_EQUIPAMENTO' AND entidade_id = ?
                """,
                equipmentId
        )).isEqualTo(beforeReplay);
    }

    @Test
    void remocaoDeFotoInativaObjetoEFechaTodasAsArestasSemReabrirNoReplay() {
        String obraId = id();
        inserirObra(obraId, "FOTO-REMOVIDA");
        String rdoId = id();
        inserirRdo(
                rdoId,
                obraId,
                "RDO-FOTO-001",
                SELECTED_DATE,
                "RASCUNHO",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        String attachmentId = id();
        jdbc.update(
                """
                INSERT INTO rdo_attachment (
                    id, rdo_id, obra_id, tipo, nome, nome_original,
                    mime_type, tamanho_original_bytes,
                    tamanho_comprimido_bytes, tamanho_bytes, sync_status
                ) VALUES (?, ?, ?, 'FOTO', 'frente.jpg', 'frente-original.jpg',
                          'image/jpeg', 1024, 768, 768, 'SYNCED')
                """,
                attachmentId,
                rdoId,
                obraId
        );
        RdoMemoryPublisher publisher = realMemoryPublisher();
        publisher.registrarRdoCriado(
                rdoId, obraId, null, "RDO-FOTO-001", "RASCUNHO"
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM cortex_relacao
                WHERE origem_tipo = 'RDO' AND origem_id = ?
                  AND destino_tipo = 'RDO_FOTO' AND destino_id = ?
                  AND tipo_relacao = 'POSSUI_FOTO' AND ativa = TRUE
                """,
                Integer.class,
                rdoId,
                attachmentId
        )).isOne();

        jdbc.update(
                "UPDATE rdo_attachment SET removido_em = now() WHERE id = ?",
                attachmentId
        );
        publisher.registrarRdoEditado(
                rdoId, obraId, null, "RDO-FOTO-001", "RASCUNHO",
                0, 1, List.of()
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT status FROM cortex_objeto
                WHERE tipo_entidade = 'RDO_FOTO' AND entidade_id = ?
                """,
                String.class,
                attachmentId
        )).isEqualTo("INATIVO");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM cortex_relacao
                WHERE ativa = TRUE
                  AND ((origem_tipo = 'RDO_FOTO' AND origem_id = ?)
                    OR (destino_tipo = 'RDO_FOTO' AND destino_id = ?))
                """,
                Integer.class,
                attachmentId,
                attachmentId
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM cortex_relacao
                WHERE origem_tipo = 'RDO' AND origem_id = ?
                  AND destino_tipo = 'RDO_FOTO' AND destino_id = ?
                  AND tipo_relacao = 'POSSUI_FOTO' AND ativa = FALSE
                """,
                Integer.class,
                rdoId,
                attachmentId
        )).isOne();
        Long inactiveVersion = jdbc.queryForObject(
                """
                SELECT versao_linha FROM cortex_objeto
                WHERE tipo_entidade = 'RDO_FOTO' AND entidade_id = ?
                """,
                Long.class,
                attachmentId
        );

        publisher.registrarRdoEditado(
                rdoId, obraId, null, "RDO-FOTO-001", "RASCUNHO",
                1, 2, List.of()
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT versao_linha FROM cortex_objeto
                WHERE tipo_entidade = 'RDO_FOTO' AND entidade_id = ?
                """,
                Long.class,
                attachmentId
        )).isEqualTo(inactiveVersion);
    }

    @Test
    void atualizacaoNaoPodeReescreverProvenienciaDaEquipe() throws Exception {
        String obraId = id();
        inserirObra(obraId, "ORIGEM-IMUTAVEL");
        String collaborator = inserirColaborador("Equipe", null, null);
        vincular(collaborator, obraId, "OPERACIONAL", "ATIVO");
        String previousRdoId = id();
        inserirRdo(
                previousRdoId,
                obraId,
                "RDO-0001",
                SELECTED_DATE.minusDays(1),
                "ENVIADO",
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().minusDays(1)
        );
        String sourceItemId = id();
        inserirMaoObra(sourceItemId, previousRdoId, collaborator, "Operador");
        String currentRdoId = id();
        String currentItemId = id();
        RdoCreateRequest create = request(
                currentRdoId,
                obraId,
                id(),
                1L,
                previousRdoId,
                collaborator,
                currentItemId,
                sourceItemId
        );
        transactions.execute(status -> service(collaborator).criarRascunho(create));
        com.fasterxml.jackson.databind.node.ObjectNode withoutOriginJson =
                mapper.valueToTree(create);
        ((com.fasterxml.jackson.databind.node.ObjectNode) withoutOriginJson
                .withArray("maoObra")
                .get(0)).putNull("origemItemId");
        RdoCreateRequest withoutOrigin = mapper.treeToValue(
                withoutOriginJson,
                RdoCreateRequest.class
        );

        assertThatThrownBy(() -> transactions.execute(
                status -> draftService().atualizarRascunho(currentRdoId, withoutOrigin)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("origem");
        assertThat(jdbc.queryForObject(
                "SELECT origem_item_id FROM rdo_mao_obra WHERE id = ?",
                String.class,
                currentItemId
        )).isEqualTo(sourceItemId);
    }

    @Test
    void atualizacaoNaoPodeMoverRdoParaDataIncompativelComOrigem() throws Exception {
        String obraId = id();
        inserirObra(obraId, "DATA-ORIGEM-IMUTAVEL");
        String collaborator = inserirColaborador("Equipe", null, null);
        vincular(collaborator, obraId, "OPERACIONAL", "ATIVO");
        String previousRdoId = id();
        inserirRdo(
                previousRdoId,
                obraId,
                "RDO-0001",
                SELECTED_DATE.minusDays(1),
                "ENVIADO",
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().minusDays(1)
        );
        String sourceItemId = id();
        inserirMaoObra(sourceItemId, previousRdoId, collaborator, "Operador");
        String currentRdoId = id();
        RdoCreateRequest create = request(
                currentRdoId,
                obraId,
                id(),
                1L,
                previousRdoId,
                collaborator,
                id(),
                sourceItemId
        );
        transactions.execute(status -> service(collaborator).criarRascunho(create));
        com.fasterxml.jackson.databind.node.ObjectNode invalidDateJson =
                mapper.valueToTree(create);
        invalidDateJson.put("dataRdo", SELECTED_DATE.minusDays(2).toString());
        RdoCreateRequest invalidDate = mapper.treeToValue(
                invalidDateJson,
                RdoCreateRequest.class
        );

        assertThatThrownBy(() -> transactions.execute(
                status -> draftService().atualizarRascunho(currentRdoId, invalidDate)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("origem");
        assertThat(jdbc.queryForObject(
                "SELECT data_rdo FROM rdo WHERE id = ?",
                LocalDate.class,
                currentRdoId
        )).isEqualTo(SELECTED_DATE);
    }

    private Callable<RdoResponse> createTask(
            CountDownLatch start,
            RdoService service,
            RdoCreateRequest request
    ) {
        return () -> {
            start.await();
            return transactions.execute(status -> service.criarRascunho(request));
        };
    }

    private RdoService service(String ownerId) {
        return service(ownerId, mock(RdoMemoryPublisher.class));
    }

    private RdoService service(String ownerId, RdoMemoryPublisher memoryPublisher) {
        return service(ownerId, memoryPublisher, jdbc);
    }

    private RdoService service(
            String ownerId,
            RdoMemoryPublisher memoryPublisher,
            JdbcTemplate serviceJdbc
    ) {
        RdoOperationalDetailService details = mock(RdoOperationalDetailService.class);
        when(details.substituirDetalhes(
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(new RdoOperationalDetailService.RdoOperationalDetails(
                List.of(), List.of()
        ));
        RdoAttachmentService attachments = mock(RdoAttachmentService.class);
        when(attachments.listar(any())).thenReturn(List.of());
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireUserId()).thenReturn(ownerId);
        return new RdoService(
                serviceJdbc,
                mapper,
                currentUserService,
                new RdoAssetEligibilityService(serviceJdbc),
                memoryPublisher,
                details,
                attachments,
                mock(RdoOperationalEventService.class),
                mock(PrevisaoFinanceiraService.class),
                new RdoQueryService(
                        serviceJdbc,
                        details,
                        attachments
                ),
                operabilityGuard(serviceJdbc)
        );
    }

    private ObraOperabilityGuard operabilityGuard(
            JdbcTemplate serviceJdbc
    ) {
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        doAnswer(invocation -> {
            String obraId = invocation.getArgument(0);
            Integer writable = serviceJdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM obra
                    WHERE id = ?
                      AND arquivado_em IS NULL
                    """,
                    Integer.class,
                    obraId
            );
            if (writable == null || writable == 0) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Obra não encontrada ou arquivada."
                );
            }
            return null;
        }).when(guard).requireWritable(any());
        return guard;
    }

    private RdoDraftUpdateService draftService() {
        return draftService(mock(RdoMemoryPublisher.class));
    }

    private RdoDraftUpdateService draftService(RdoMemoryPublisher memoryPublisher) {
        RdoOperationalDetailService details = mock(RdoOperationalDetailService.class);
        RdoAttachmentService attachments = mock(RdoAttachmentService.class);
        when(details.listarServicos(any())).thenReturn(List.of());
        when(details.listarAlocacoes(any())).thenReturn(List.of());
        when(attachments.listar(any())).thenReturn(List.of());
        return new RdoDraftUpdateService(
                jdbc,
                new RdoQueryService(jdbc, details, attachments),
                new RdoAssetEligibilityService(jdbc),
                memoryPublisher,
                new RdoChangeAuditService(jdbc),
                details,
                attachments,
                mock(RdoOperationalEventService.class),
                mock(PrevisaoFinanceiraService.class),
                operabilityGuard(jdbc)
        );
    }

    private RdoMemoryPublisher realMemoryPublisher() {
        return new RdoMemoryPublisher(
                new CortexOperationalMemoryService(
                        jdbc,
                        mapper,
                        mock(ApplicationEventPublisher.class)
                ),
                jdbc
        );
    }

    private RdoCreateRequest request(
            String rdoId,
            String obraId,
            String mutationId,
            long contextVersion,
            String previousRdoId,
            String colaboradorId,
            String workforceItemId,
            String originItemId
    ) throws Exception {
        RdoContextResponse context = new RdoContextService(jdbc)
                .buscarContexto(obraId, SELECTED_DATE);
        String requestPreviousRdoId = previousRdoId;
        if (requestPreviousRdoId == null && context.previousRdo() != null) {
            requestPreviousRdoId = context.previousRdo().id();
        }
        return mapper.readValue("""
                {
                  "id":"%s",
                  "obraId":"%s",
                  "dataRdo":"2026-07-22",
                  "previousRdoId":%s,
                  "creationContextVersion":%d,
                  "clientMutationId":"%s",
                  "apontadorColaboradorId":"%s",
                  "maoObra":[{
                    "id":"%s",
                    "colaboradorId":"%s",
                    "nomeColaborador":"Equipe",
                    "cargo":"OPERACIONAL",
                    "tipoVinculo":"CONTRATADO",
                    "origemItemId":%s
                  }]
                }
                """.formatted(
                rdoId,
                obraId,
                requestPreviousRdoId == null ? "null" : "\"" + requestPreviousRdoId + "\"",
                context.provenance().receiptVersion(),
                mutationId,
                colaboradorId,
                workforceItemId,
                colaboradorId,
                originItemId == null ? "null" : "\"" + originItemId + "\""
        ), RdoCreateRequest.class);
    }

    private void inserirObra(String obraId, String suffix) {
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, codigo_cw, nome) VALUES (?, ?, ?, ?)",
                obraId, "CTR-" + suffix, "CW-" + suffix, "Obra " + suffix
        );
    }

    private ContextSnapshotTuple contextSnapshotTuple(long receiptVersion) {
        return jdbc.queryForObject(
                """
                SELECT
                    xmin::text AS xmin,
                    ctid::text AS ctid,
                    generated_at,
                    stale_after
                FROM rdo_creation_context_snapshot
                WHERE receipt_version = ?
                """,
                (rs, rowNumber) -> new ContextSnapshotTuple(
                        rs.getString("xmin"),
                        rs.getString("ctid"),
                        rs.getTimestamp("generated_at").toInstant(),
                        rs.getTimestamp("stale_after").toInstant()
                ),
                receiptVersion
        );
    }

    private void inserirReceiptsCanonicos(
            String obraId,
            int quantidade,
            boolean expirados
    ) {
        inserirReceiptsCanonicos(obraId, quantidade, expirados, null);
    }

    private void inserirReceiptsCanonicos(
            String obraId,
            int quantidade,
            boolean expirados,
            String issuedById
    ) {
        String fixtureSeed = obraId + ":" + (
                issuedById == null ? "sem-ator" : issuedById
        );
        jdbc.update(
                """
                INSERT INTO rdo_creation_context_snapshot (
                    snapshot_id, obra_id, selected_date, previous_rdo_id,
                    source_version, payload_hash, coverage_json,
                    generated_at, stale_after, canonical_key, issued_by_id
                )
                SELECT
                    md5(? || '-snapshot-' || serie::text),
                    ?,
                    ?,
                    NULL,
                    serie,
                    md5(? || '-payload-a-' || serie::text)
                        || md5(? || '-payload-b-' || serie::text),
                    '{}'::jsonb,
                    CASE
                        WHEN ? THEN now() - interval '100 days'
                        ELSE now()
                    END,
                    CASE
                        WHEN ? THEN now() - interval '99 days'
                        ELSE now() + interval '15 minutes'
                    END,
                    md5(? || '-canonical-a-' || serie::text)
                        || md5(? || '-canonical-b-' || serie::text),
                    ?
                FROM generate_series(1, ?) AS serie
                """,
                fixtureSeed,
                obraId,
                SELECTED_DATE,
                fixtureSeed,
                fixtureSeed,
                expirados,
                expirados,
                fixtureSeed,
                fixtureSeed,
                issuedById,
                quantidade
        );
    }

    private String inserirColaborador(String nome, String email, String cpfMascarado) {
        String colaboradorId = id();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    codigo_colaborador, nome, email, cpf_mascarado, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, ?, ?, ?, ?, 'BETA')
                """, colaboradorId, colaboradorId, colaboradorId.substring(0, 8),
                nome, email, cpfMascarado);
        return colaboradorId;
    }

    private void vincular(
            String colaboradorId,
            String obraId,
            String papel,
            String status
    ) {
        jdbc.update("""
                INSERT INTO vinculo_colaborador_obra (
                    id, obra_id, colaborador_id, status, papel_na_obra
                ) VALUES (?, ?, ?, ?, ?)
                """, id(), obraId, colaboradorId, status, papel);
    }

    private void inserirRdo(
            String rdoId,
            String obraId,
            String numero,
            LocalDate data,
            String status,
            LocalDateTime criadoEm,
            LocalDateTime atualizadoEm
    ) {
        jdbc.update("""
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo, status, criado_em, atualizado_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, rdoId, obraId, numero, data, status, criadoEm, atualizadoEm);
    }

    private void inserirMaoObra(
            String itemId,
            String rdoId,
            String colaboradorId,
            String cargo
    ) {
        jdbc.update("""
                INSERT INTO rdo_mao_obra (
                    id, rdo_id, colaborador_id, nome_colaborador, cargo
                ) VALUES (?, ?, ?, 'Snapshot', ?)
                """, itemId, rdoId, colaboradorId, cargo);
    }

    private String inserirAsset(String code) {
        String assetId = id();
        jdbc.update("""
                INSERT INTO asset (
                    id, source_database, source_table, source_pk,
                    external_code, name, category
                ) VALUES (?, 'fixture', 'asset', ?, ?, ?, 'EQUIPAMENTO')
                """, assetId, assetId, code, "Equipamento " + code);
        return assetId;
    }

    private void inserirEquipamento(String itemId, String rdoId, String assetId) {
        String obraId = jdbc.queryForObject(
                "SELECT obra_id FROM rdo WHERE id = ?",
                String.class,
                rdoId
        );
        tornarAssetElegivel(assetId, obraId);
        inserirEquipamentoSemElegibilidade(itemId, rdoId, assetId);
    }

    private void inserirEquipamentoSemElegibilidade(
            String itemId,
            String rdoId,
            String assetId
    ) {
        jdbc.update("""
                INSERT INTO rdo_equipamento (
                    id, rdo_id, asset_id, prefixo, descricao
                ) VALUES (?, ?, ?, 'EQ', 'Equipamento')
                """, itemId, rdoId, assetId);
    }

    private void tornarAssetElegivel(String assetId, String obraId) {
        jdbc.update("""
                INSERT INTO asset_obra_eligibilidade (
                    asset_id, obra_id, status, origem
                ) VALUES (?, ?, 'ATIVO', 'TESTE')
                """, assetId, obraId);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timeout aguardando coordenação do teste.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitWaitingAdvisoryLock(Future<?> operation)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (operation.isDone()) {
                return;
            }
            Integer waiting = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM pg_locks
                    WHERE locktype = 'advisory'
                      AND granted = FALSE
                    """,
                    Integer.class
            );
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "A retenção não aguardou o lock consultivo do receipt."
        );
    }

    private static final class PausingCanonicalLookupJdbcTemplate
            extends JdbcTemplate {

        private final CountDownLatch lookupCompleted;
        private final CountDownLatch allowReturn;
        private boolean intercepted;

        private PausingCanonicalLookupJdbcTemplate(
                DriverManagerDataSource coordinatedDataSource,
                CountDownLatch lookupCompleted,
                CountDownLatch allowReturn
        ) {
            super(coordinatedDataSource);
            this.lookupCompleted = lookupCompleted;
            this.allowReturn = allowReturn;
        }

        @Override
        public <T> T query(
                String sql,
                ResultSetExtractor<T> resultSetExtractor,
                Object... args
        ) {
            T result = super.query(sql, resultSetExtractor, args);
            if (!intercepted
                    && sql.contains("FROM rdo_creation_context_snapshot")
                    && sql.contains("WHERE canonical_key = ?")) {
                intercepted = true;
                lookupCompleted.countDown();
                await(allowReturn);
            }
            return result;
        }
    }

    private static final class PausingProvenanceLockJdbcTemplate
            extends JdbcTemplate {

        private final CountDownLatch provenanceLocked;
        private final CountDownLatch allowCreation;
        private boolean intercepted;

        private PausingProvenanceLockJdbcTemplate(
                DriverManagerDataSource coordinatedDataSource,
                CountDownLatch provenanceLocked,
                CountDownLatch allowCreation
        ) {
            super(coordinatedDataSource);
            this.provenanceLocked = provenanceLocked;
            this.allowCreation = allowCreation;
        }

        @Override
        public <T> List<T> query(
                String sql,
                RowMapper<T> rowMapper,
                Object... args
        ) {
            List<T> rows = super.query(sql, rowMapper, args);
            if (!intercepted
                    && sql.contains("FROM rdo_creation_context_snapshot")
                    && sql.contains("FOR KEY SHARE")) {
                intercepted = true;
                provenanceLocked.countDown();
                await(allowCreation);
            }
            return rows;
        }
    }

    /*
     * O defeito que o campo cobrou: o catálogo lia apenas
     * vinculo_colaborador_obra, então numa obra com equipe montada e gente
     * dentro dela a busca do RDO respondia "nenhum colaborador autorizado
     * encontrado". Vincular pessoa a pessoa era a única forma de fazer o
     * apontamento enxergar alguém — trabalho à mão sobre informação que a
     * equipe já continha.
     *
     * O caso é montado com duas equipes na mesma obra de propósito, porque é
     * assim que a obra real funciona, e com uma pessoa nas duas: se a união
     * fosse por equipe em vez de por colaborador, ela viraria duas linhas
     * iguais na lista de escolha.
     */
    @Test
    void catalogoUneVinculoDiretoEMembrosDeTodasAsEquipesVigentesDaObra() {
        String obra = id();
        inserirObra(obra, "Uniao");
        String autor = inserirColaborador(
                "Autor", "autor@fixture.invalid", "***.900.***-**");

        String porVinculo = inserirColaborador(
                "Ana Vinculo", "ana@fixture.invalid", "***.901.***-**");
        String soNaEquipeA = inserirColaborador(
                "Bruno Equipe A", "bruno@fixture.invalid", "***.902.***-**");
        String soNaEquipeB = inserirColaborador(
                "Carla Equipe B", "carla@fixture.invalid", "***.903.***-**");
        String nasDuasEquipes = inserirColaborador(
                "Duda Nas Duas", "duda@fixture.invalid", "***.904.***-**");
        String deEquipeArquivada = inserirColaborador(
                "Elis Arquivada", "elis@fixture.invalid", "***.905.***-**");
        String removidoDaEquipe = inserirColaborador(
                "Fabio Removido", "fabio@fixture.invalid", "***.906.***-**");
        String deEquipeQueSaiuDaObra = inserirColaborador(
                "Gil Encerrada", "gil@fixture.invalid", "***.907.***-**");

        vincular(porVinculo, obra, "APONTADOR", "ATIVO");

        String equipeA = inserirEquipe(obra, "Frente A", "ATIVA", autor);
        String equipeB = inserirEquipe(obra, "Frente B", "ATIVA", autor);
        String equipeArquivada =
                inserirEquipe(obra, "Frente Velha", "ARQUIVADA", autor);
        String equipeForaDaObra =
                inserirEquipe(obra, "Frente Que Saiu", "ATIVA", autor);

        alocarEquipeNaObra(equipeA, obra, "ATIVO");
        alocarEquipeNaObra(equipeB, obra, "ATIVO");
        alocarEquipeNaObra(equipeArquivada, obra, "ATIVO");
        alocarEquipeNaObra(equipeForaDaObra, obra, "ENCERRADO");

        inserirMembro(equipeA, soNaEquipeA, autor, "ATIVO");
        inserirMembro(equipeA, nasDuasEquipes, autor, "ATIVO");
        inserirMembro(equipeB, nasDuasEquipes, autor, "ATIVO");
        inserirMembro(equipeB, soNaEquipeB, autor, "ATIVO");
        inserirMembro(equipeA, removidoDaEquipe, autor, "REMOVIDO");
        inserirMembro(equipeArquivada, deEquipeArquivada, autor, "ATIVO");
        inserirMembro(equipeForaDaObra, deEquipeQueSaiuDaObra, autor, "ATIVO");

        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obra, SELECTED_DATE);

        assertThat(response.colaboradores())
                .extracting(RdoContextResponse.ColaboradorContexto::id)
                .containsExactlyInAnyOrder(
                        porVinculo, soNaEquipeA, soNaEquipeB, nasDuasEquipes);

        // Quem está em duas frentes é uma pessoa, não duas escolhas iguais.
        assertThat(response.colaboradores())
                .filteredOn(item -> item.id().equals(nasDuasEquipes))
                .hasSize(1);

        // Vigência dos dois lados: equipe arquivada, alocação encerrada e
        // membro removido não autorizam mais ninguém a ser apontado.
        assertThat(response.colaboradores())
                .extracting(RdoContextResponse.ColaboradorContexto::id)
                .doesNotContain(
                        deEquipeArquivada, removidoDaEquipe, deEquipeQueSaiuDaObra);
    }

    /*
     * O mesmo furo aparecia disfarçado no RDO anterior: quem entrou na obra por
     * equipe voltava marcado como indisponível, como se tivesse saído da obra.
     */
    @Test
    void equipeDoRdoAnteriorFicaDisponivelParaQuemEntrouPorEquipe() {
        String obra = id();
        inserirObra(obra, "Disponibilidade");
        String autor = inserirColaborador(
                "Autor D", "autord@fixture.invalid", "***.910.***-**");
        String soNaEquipe = inserirColaborador(
                "Helena Equipe", "helena@fixture.invalid", "***.911.***-**");
        String semNada = inserirColaborador(
                "Ivo Sem Nada", "ivo@fixture.invalid", "***.912.***-**");

        String equipe = inserirEquipe(obra, "Frente Unica", "ATIVA", autor);
        alocarEquipeNaObra(equipe, obra, "ATIVO");
        inserirMembro(equipe, soNaEquipe, autor, "ATIVO");

        LocalDateTime quando = LocalDateTime.of(2026, 7, 21, 18, 0);
        String anterior = id();
        inserirRdo(anterior, obra, "RDO-0100",
                SELECTED_DATE.minusDays(1), "ENVIADO", quando, quando);
        inserirMaoObra("item-equipe-" + id().substring(0, 12),
                anterior, soNaEquipe, "Operadora");
        inserirMaoObra("item-sem-" + id().substring(0, 12),
                anterior, semNada, "Operador");

        RdoContextResponse response = new RdoContextService(jdbc)
                .buscarContexto(obra, SELECTED_DATE);

        assertThat(response.previousWorkforce())
                .filteredOn(item -> item.collaboratorId().equals(soNaEquipe))
                .singleElement()
                .extracting(RdoContextResponse.PreviousWorkforceItem::availability)
                .isEqualTo("AVAILABLE");
        assertThat(response.previousWorkforce())
                .filteredOn(item -> item.collaboratorId().equals(semNada))
                .singleElement()
                .extracting(RdoContextResponse.PreviousWorkforceItem::availability)
                .isEqualTo("UNAVAILABLE");
    }

    private String inserirEquipe(
            String obraId,
            String nome,
            String status,
            String autorId
    ) {
        String equipeId = id();
        jdbc.update("""
                INSERT INTO equipe (
                    id, obra_id, obra_principal_id, nome, status,
                    criado_por, atualizado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, equipeId, obraId, obraId, nome, status, autorId, autorId);
        return equipeId;
    }

    private void alocarEquipeNaObra(
            String equipeId,
            String obraId,
            String status
    ) {
        jdbc.update("""
                INSERT INTO equipe_obra (
                    id, equipe_id, obra_id, status, inicio_em, fim_em,
                    atribuido_por
                ) VALUES (?, ?, ?, ?, ?, ?, 'fixture')
                """, id(), equipeId, obraId, status,
                LocalDateTime.of(2026, 7, 1, 8, 0),
                "ENCERRADO".equals(status)
                        ? LocalDateTime.of(2026, 7, 10, 8, 0)
                        : null);
    }

    private void inserirMembro(
            String equipeId,
            String colaboradorId,
            String autorId,
            String status
    ) {
        // inicio_em explícito: o default é o agora, e chk_equipe_membro_periodo
        // exige fim_em >= inicio_em. Encerrar em julho com início hoje viola a
        // restrição — o banco recusa antes de o teste chegar à asserção.
        jdbc.update("""
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, status, inicio_em, fim_em,
                    adicionado_por, atribuido_por, atualizado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id(), equipeId, colaboradorId, status,
                LocalDateTime.of(2026, 7, 1, 8, 0),
                "ATIVO".equals(status)
                        ? null
                        : LocalDateTime.of(2026, 7, 15, 8, 0),
                autorId, autorId, autorId);
    }

    private record ContextSnapshotTuple(
            String xmin,
            String ctid,
            java.time.Instant generatedAt,
            java.time.Instant staleAfter
    ) {
    }
}

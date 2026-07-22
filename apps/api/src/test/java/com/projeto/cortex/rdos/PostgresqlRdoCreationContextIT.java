package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
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

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void upgradeV48PreservaNumerosLegadosDuplicadosSemBloquearNovosAutoritativos() {
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
                SELECTED_DATE, "RASCUNHO", empate.plusHours(1), empate.plusHours(1));
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
        assertThat(response.provenance().previousRdoId()).isEqualTo(anterior);
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
        assertThat(response.coverage().serviceCatalog().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(response.coverage().priceCatalog().status()).isEqualTo("NOT_CONFIGURED");
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
        RdoService service = service(colaborador);

        RdoResponse first = transactions.execute(status -> service.criarRascunho(request));
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
        invalidDateJson.put("dataRdo", SELECTED_DATE.minusDays(1).toString());
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
                jdbc,
                mapper,
                currentUserService,
                new RdoAssetEligibilityService(jdbc),
                memoryPublisher,
                details,
                attachments,
                mock(RdoOperationalEventService.class),
                mock(PrevisaoFinanceiraService.class),
                new RdoQueryService(
                        jdbc,
                        details,
                        attachments
                )
        );
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
                mock(PrevisaoFinanceiraService.class)
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
}

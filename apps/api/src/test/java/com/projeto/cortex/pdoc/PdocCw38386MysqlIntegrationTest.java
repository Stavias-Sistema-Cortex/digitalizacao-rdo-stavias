package com.projeto.cortex.pdoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.intelligence.PdocContextBuilder;
import com.projeto.cortex.intelligence.PdocEngine;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import com.projeto.cortex.obras.ObraSeedImportService;
import com.projeto.cortex.programacoes.ProgramacaoSeedImportService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "cortex.sync.enabled=false",
        "cortex.import.enabled=false",
        "cortex.auth.jwt-secret=test-only-jwt-secret-0000000000000000",
        "spring.jpa.hibernate.ddl-auto=none",
        "debug=false",
        "logging.level.root=INFO",
        "logging.level.org.springframework=INFO"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CORTEX_MYSQL_ROOT_PASSWORD", matches = ".+")
class PdocCw38386MysqlIntegrationTest {

    private static PdocMysqlTestDatabase database;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        database = PdocMysqlTestDatabase.create("cw38386");
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", PdocMysqlTestDatabase::rootPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObraRepository obraRepository;

    @Autowired
    private ObraSeedImportService obraSeedImportService;

    @Autowired
    private ProgramacaoSeedImportService programacaoSeedImportService;

    @Autowired
    private RealPdocInputLoader inputLoader;

    @BeforeEach
    void setUp() {
        limparDadosDeSeed();
        obraSeedImportService.importarSeedPadrao();
        programacaoSeedImportService.importarSeedPadrao();

        assertThat(contarProgramacoesCw38386()).isEqualTo(172);
    }

    @AfterAll
    static void tearDownDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void shouldExecuteRealCw38386InsufficientDataFlow() throws Exception {
        Obra obra = localizarCw38386();
        PdocInputBundle loaded = inputLoader.load(obra, null);

        assertThat(obra.getCodigoContrato()).isEqualTo("CW38386");
        assertThat(loaded.referenceDate()).isEqualTo(LocalDate.of(2026, 6, 8));
        assertThat(loaded.inputs().get("programacaoRows")).isEqualTo(172);
        assertThat(loaded.inputs().get("scheduleStartDate")).isEqualTo(LocalDate.of(2025, 12, 10));
        assertThat(loaded.inputs().get("scheduleEndDate")).isEqualTo(LocalDate.of(2026, 6, 8));
        assertThat(loaded.inputs().get("rdoRows")).isEqualTo(0);

        MvcResult firstResult = mockMvc.perform(
                        post("/api/obras/{obraId}/pdoc/calcular", "CW38386")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusExecucao").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.statusExecucaoLabel").value("Dados insuficientes"))
                .andExpect(jsonPath("$.dataReferencia").value("2026-06-08"))
                .andExpect(jsonPath("$.snapshotExistente").value(false))
                .andExpect(jsonPath("$.inputs.programacaoRows").value(172))
                .andExpect(jsonPath("$.inputs.rdoRows").value(0))
                .andExpect(jsonPath("$.inputs.approvedBudget").isEmpty())
                .andExpect(jsonPath("$.inputs.actualCost").isEmpty())
                .andExpect(jsonPath("$.inputs.committedCost").isEmpty())
                .andExpect(jsonPath("$.inputs.actualExecutedQuantity").isEmpty())
                .andExpect(jsonPath("$.p10").isEmpty())
                .andExpect(jsonPath("$.p50").isEmpty())
                .andExpect(jsonPath("$.p80").isEmpty())
                .andExpect(jsonPath("$.p95").isEmpty())
                .andExpect(jsonPath("$.eacs.cpi").isEmpty())
                .andExpect(jsonPath("$.eacs.cpiSpi").isEmpty())
                .andExpect(jsonPath("$.eacs.bottomUp").isEmpty())
                .andExpect(jsonPath("$.eacs.ponderado").isEmpty())
                .andExpect(jsonPath("$.probabilidadeQualquerExcedente").isEmpty())
                .andExpect(jsonPath("$.probabilidadeExceder5Pct").isEmpty())
                .andExpect(jsonPath("$.probabilidadeExceder10Pct").isEmpty())
                .andExpect(jsonPath("$.scoreHeuristico").isEmpty())
                .andExpect(jsonPath("$.confianca").isEmpty())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(
                firstResult.getResponse().getContentAsString(StandardCharsets.UTF_8)
        );
        String snapshotId = firstJson.get("id").asText();
        JsonNode inputs = firstJson.get("inputs");

        assertThat(inputs.get("totalPlannedQuantity").decimalValue())
                .isEqualByComparingTo("152481.093");
        assertThat(inputs.get("plannedExecutedQuantity").decimalValue())
                .isEqualByComparingTo("152481.093");
        assertThat(firstJson.at("/origemDados/approvedBudget/availability").asText())
                .isEqualTo("ABSENT");
        assertThat(firstJson.at("/origemDados/actualCost/availability").asText())
                .isEqualTo("ABSENT");
        assertThat(firstJson.at("/origemDados/committedCost/availability").asText())
                .isEqualTo("ABSENT");
        assertThat(firstJson.get("warnings").toString())
                .contains("Orçamento total aprovado ausente")
                .contains("Custo realizado ausente")
                .contains("Custo comprometido ausente")
                .contains("Nenhum RDO associado encontrado");

        mockMvc.perform(get("/api/obras/{obraId}/pdoc/atual", "CW38386"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(snapshotId))
                .andExpect(jsonPath("$.statusExecucao").value("INSUFFICIENT_DATA"));

        mockMvc.perform(get("/api/obras/{obraId}/pdoc/historico", "CW38386"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(snapshotId))
                .andExpect(jsonPath("$.totalElements").value(1));

        MvcResult secondResult = mockMvc.perform(
                        post("/api/obras/{obraId}/pdoc/calcular", "CW38386")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(snapshotId))
                .andExpect(jsonPath("$.snapshotExistente").value(true))
                .andReturn();

        JsonNode secondJson = objectMapper.readTree(
                secondResult.getResponse().getContentAsString(StandardCharsets.UTF_8)
        );
        assertThat(secondJson.get("id").asText()).isEqualTo(snapshotId);
        assertThat(contarSnapshots()).isEqualTo(1);
        assertThat(statusSnapshot(snapshotId)).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void shouldNotRunEngineForRealCw38386InsufficientData() {
        PdocSnapshotRepository snapshotRepository =
                new PdocSnapshotRepository(jdbcTemplate, objectMapper);
        PdocEngine engine = mock(PdocEngine.class);
        PdocApplicationService service = new PdocApplicationService(
                obraRepository,
                inputLoader,
                snapshotRepository,
                objectMapper,
                new PdocContextBuilder(),
                engine
        );

        PdocResultadoResponse response =
                service.calcular("CW38386", null, PdocTriggerType.API, null);

        assertThat(response.statusExecucao()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.p50()).isNull();
        verifyNoInteractions(engine);
    }

    @Test
    void shouldHandleConcurrentRealRequestsWithDatabaseUniqueConstraint() throws Exception {
        BarrierSnapshotRepository raceRepository =
                new BarrierSnapshotRepository(jdbcTemplate, objectMapper, 2);
        PdocApplicationService raceService = new PdocApplicationService(
                obraRepository,
                inputLoader,
                raceRepository,
                objectMapper,
                new PdocContextBuilder(),
                new PdocEngine()
        );
        MockMvc raceMvc = MockMvcBuilders
                .standaloneSetup(new PdocController(
                        raceService,
                        mock(CurrentUserService.class)
                ))
                .setControllerAdvice(new PdocExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> request = () -> raceMvc.perform(
                            post("/api/obras/{obraId}/pdoc/calcular", "CW38386")
                    )
                    .andReturn();

            List<Future<MvcResult>> futures = executorService.invokeAll(
                    List.of(request, request),
                    20,
                    TimeUnit.SECONDS
            );
            List<MvcResult> results = futures.stream()
                    .map(this::getResult)
                    .toList();

            assertThat(results).hasSize(2);
            assertThat(results)
                    .allSatisfy(result -> assertThat(result.getResponse().getStatus())
                            .isEqualTo(200));
            assertThat(results)
                    .noneSatisfy(result -> assertThat(result.getResponse().getStatus())
                            .isEqualTo(500));

            Set<String> ids = results.stream()
                    .map(result -> readJson(result).get("id").asText())
                    .collect(Collectors.toSet());

            assertThat(ids).hasSize(1);
            assertThat(contarSnapshots()).isEqualTo(1);
            assertThat(raceRepository.duplicateInsertCount()).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
        }
    }

    private MvcResult getResult(Future<MvcResult> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Falha ao obter resposta HTTP concorrente.", exception);
        }
    }

    private JsonNode readJson(MvcResult result) {
        try {
            return objectMapper.readTree(
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8)
            );
        } catch (Exception exception) {
            throw new AssertionError("Resposta HTTP não é JSON válido.", exception);
        }
    }

    private void limparDadosDeSeed() {
        jdbcTemplate.update("DELETE FROM pdoc_snapshot");
        jdbcTemplate.update("DELETE FROM programacao_operacional");
        jdbcTemplate.update("DELETE FROM obra");
    }

    private Obra localizarCw38386() {
        List<Obra> obras = obraRepository.findAtivasByIdentificador("CW38386");
        assertThat(obras).hasSize(1);
        return obras.getFirst();
    }

    private long contarProgramacoesCw38386() {
        Long total = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM programacao_operacional
                WHERE codigo_contrato_origem = 'CW38386'
                """,
                Long.class
        );
        return total == null ? 0 : total;
    }

    private long contarSnapshots() {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pdoc_snapshot",
                Long.class
        );
        return total == null ? 0 : total;
    }

    private String statusSnapshot(String snapshotId) {
        return jdbcTemplate.queryForObject(
                "SELECT status_execucao FROM pdoc_snapshot WHERE id = ?",
                String.class,
                snapshotId
        );
    }

    private static final class BarrierSnapshotRepository
            extends PdocSnapshotRepository {

        private final CyclicBarrier barrier;
        private final AtomicInteger emptyFinds = new AtomicInteger();
        private final AtomicInteger duplicateInserts = new AtomicInteger();
        private final int participants;

        private BarrierSnapshotRepository(
                JdbcTemplate jdbcTemplate,
                ObjectMapper objectMapper,
                int participants
        ) {
            super(jdbcTemplate, objectMapper);
            this.participants = participants;
            this.barrier = new CyclicBarrier(participants);
        }

        @Override
        public java.util.Optional<PdocSnapshot> findByIdempotencyKey(
                String idempotencyKey
        ) {
            java.util.Optional<PdocSnapshot> snapshot =
                    super.findByIdempotencyKey(idempotencyKey);
            if (snapshot.isEmpty()
                    && emptyFinds.incrementAndGet() <= participants) {
                awaitBothRequestsAfterEmptyRead();
            }
            return snapshot;
        }

        @Override
        public void insert(PdocSnapshot snapshot) throws DuplicateKeyException {
            try {
                super.insert(snapshot);
            } catch (DuplicateKeyException exception) {
                duplicateInserts.incrementAndGet();
                throw exception;
            }
        }

        private int duplicateInsertCount() {
            return duplicateInserts.get();
        }

        private void awaitBothRequestsAfterEmptyRead() {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "As chamadas concorrentes não chegaram juntas à leitura vazia.",
                        exception
                );
            }
        }
    }
}

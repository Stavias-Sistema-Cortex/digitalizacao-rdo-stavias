package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.ClientInstanceProof;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import com.projeto.cortex.intelligence.PdorContextBuilder;
import com.projeto.cortex.intelligence.PdorEngine;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.servlet.http.Cookie;
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
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "cortex.sync.enabled=false",
        "cortex.import.enabled=false",
        "cortex.pdor.gatilho-evento.habilitado=false",
        "cortex.auth.cpf-hmac.current-key-id=test-current",
        "cortex.auth.cpf-hmac.current-key-inline=test-only-hmac-secret-0000000000000000",
        "cortex.email.provider=fake",
        "spring.jpa.hibernate.ddl-auto=none",
        "debug=false",
        "logging.level.root=INFO",
        "logging.level.org.springframework=INFO"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CORTEX_MYSQL_ROOT_PASSWORD", matches = ".+")
class PdorCw38386MysqlIntegrationTest {

    private static final String ADMIN_USER_ID =
            "00000000-0000-4000-8000-00000000ad01";

    private static PdorMysqlTestDatabase database;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        database = PdorMysqlTestDatabase.create("cw38386");
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", PdorMysqlTestDatabase::rootPassword);
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
    private RealPdorInputLoader inputLoader;

    @Autowired
    private AuthSessionService authSessionService;

    private IssuedAuthSession adminSession;

    @BeforeEach
    void setUp() {
        limparDadosDeSeed();
        obraSeedImportService.importarSeedPadrao();
        programacaoSeedImportService.importarSeedPadrao();
        criarColaboradorAdmin();
        adminSession = authSessionService.issue(new AuthenticatedIdentity(
                ADMIN_USER_ID,
                "Admin PDOR Teste",
                PapelAcesso.ALFA
        ), ClientInstanceProof.fromRawValue("A".repeat(43)).orElseThrow());

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
        PdorInputBundle loaded = inputLoader.load(obra, null);

        assertThat(obra.getCodigoContrato()).isEqualTo("CW38386");
        assertThat(loaded.referenceDate()).isEqualTo(LocalDate.of(2026, 6, 8));
        assertThat(loaded.inputs().get("programacaoRows")).isEqualTo(172);
        assertThat(loaded.inputs().get("scheduleStartDate")).isEqualTo(LocalDate.of(2025, 12, 10));
        assertThat(loaded.inputs().get("scheduleEndDate")).isEqualTo(LocalDate.of(2026, 6, 8));
        assertThat(loaded.inputs().get("rdoRows")).isEqualTo(0);

        MvcResult firstResult = mockMvc.perform(
                        post("/api/obras/{obraId}/pdor/calcular", "CW38386")
                                .cookie(sessionCookie(), csrfCookie())
                                .header(
                                        "X-CSRF-Token",
                                        adminSession.csrfToken()
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusExecucao").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.statusExecucaoLabel").value("Dados insuficientes"))
                .andExpect(jsonPath("$.dataReferencia").value("2026-06-08"))
                .andExpect(jsonPath("$.snapshotExistente").value(false))
                .andExpect(jsonPath("$.inputs.programacaoRows").value(172))
                .andExpect(jsonPath("$.inputs.rdoRows").value(0))
                .andExpect(jsonPath("$.inputs.contractValue").isEmpty())
                .andExpect(jsonPath("$.inputs.measuredRevenue").isEmpty())
                .andExpect(jsonPath("$.inputs.validatedRevenue").isEmpty())
                .andExpect(jsonPath("$.inputs.actualExecutedQuantity").isEmpty())
                .andExpect(jsonPath("$.p10").isEmpty())
                .andExpect(jsonPath("$.p50").isEmpty())
                .andExpect(jsonPath("$.p80").isEmpty())
                .andExpect(jsonPath("$.p95").isEmpty())
                .andExpect(jsonPath("$.racs.rci").isEmpty())
                .andExpect(jsonPath("$.racs.rciSpi").isEmpty())
                .andExpect(jsonPath("$.racs.bottomUp").isEmpty())
                .andExpect(jsonPath("$.racs.ponderado").isEmpty())
                .andExpect(jsonPath("$.probabilidadeAbaixoContrato").isEmpty())
                .andExpect(jsonPath("$.probabilidadeAbaixo95Pct").isEmpty())
                .andExpect(jsonPath("$.probabilidadeAbaixo90Pct").isEmpty())
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
        assertThat(firstJson.at("/origemDados/contractValue/availability").asText())
                .isEqualTo("ABSENT");
        assertThat(firstJson.at("/origemDados/measuredRevenue/availability").asText())
                .isEqualTo("ABSENT");
        assertThat(firstJson.at("/origemDados/validatedRevenue/availability").asText())
                .isEqualTo("ABSENT");
        assertThat(firstJson.get("warnings").toString())
                .contains("Valor contratual ausente")
                .contains("Receita medida ausente")
                .contains("Receita validada ausente")
                .contains("Nenhum RDO associado encontrado")
                .contains("Histórico semanal de produtividade insuficiente para apoio histórico");

        mockMvc.perform(get("/api/obras/{obraId}/pdor/atual", "CW38386")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(snapshotId))
                .andExpect(jsonPath("$.statusExecucao").value("INSUFFICIENT_DATA"));

        mockMvc.perform(get("/api/obras/{obraId}/pdor/historico", "CW38386")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(snapshotId))
                .andExpect(jsonPath("$.totalElements").value(1));

        MvcResult secondResult = mockMvc.perform(
                        post("/api/obras/{obraId}/pdor/calcular", "CW38386")
                                .cookie(sessionCookie(), csrfCookie())
                                .header(
                                        "X-CSRF-Token",
                                        adminSession.csrfToken()
                                )
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
        PdorSnapshotRepository snapshotRepository =
                new PdorSnapshotRepository(jdbcTemplate, objectMapper);
        PdorEngine engine = mock(PdorEngine.class);
        PdorApplicationService service = new PdorApplicationService(
                obraRepository,
                inputLoader,
                snapshotRepository,
                objectMapper,
                mock(CortexOperationalMemoryService.class),
                new PdorContextBuilder(),
                engine
        );

        PdorResultadoResponse response =
                service.calcular("CW38386", null, PdorTriggerType.API, null);

        assertThat(response.statusExecucao()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.p50()).isNull();
        verifyNoInteractions(engine);
    }

    @Test
    void shouldHandleConcurrentRealRequestsWithDatabaseUniqueConstraint() throws Exception {
        BarrierSnapshotRepository raceRepository =
                new BarrierSnapshotRepository(jdbcTemplate, objectMapper, 2);
        PdorApplicationService raceService = new PdorApplicationService(
                obraRepository,
                inputLoader,
                raceRepository,
                objectMapper,
                mock(CortexOperationalMemoryService.class),
                new PdorContextBuilder(),
                new PdorEngine()
        );
        MockMvc raceMvc = MockMvcBuilders
                .standaloneSetup(new PdorController(
                        raceService,
                        mock(com.projeto.cortex.financeiro.access.FinancialAccessService.class)
                ))
                .setControllerAdvice(new PdorExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> request = () -> raceMvc.perform(
                            post("/api/obras/{obraId}/pdor/calcular", "CW38386")
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
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM pdor_snapshot");
        jdbcTemplate.update("DELETE FROM programacao_operacional");
        jdbcTemplate.update("DELETE FROM obra");
        jdbcTemplate.update(
                "DELETE FROM colaborador WHERE id = ?",
                ADMIN_USER_ID
        );
    }

    private void criarColaboradorAdmin() {
        jdbcTemplate.update(
                """
                INSERT INTO colaborador (
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    nome,
                    nome_perfil,
                    papel_acesso,
                    ativo
                ) VALUES (?, 'teste', 'teste', ?, 'Admin PDOR Teste', 'ADMINISTRADOR', 'ALFA', 1)
                """,
                ADMIN_USER_ID,
                ADMIN_USER_ID
        );
    }

    private Cookie sessionCookie() {
        return new Cookie(
                AuthCookieService.SESSION_COOKIE,
                adminSession.sessionToken()
        );
    }

    private Cookie csrfCookie() {
        return new Cookie(
                AuthCookieService.CSRF_COOKIE,
                adminSession.csrfToken()
        );
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
                "SELECT COUNT(*) FROM pdor_snapshot",
                Long.class
        );
        return total == null ? 0 : total;
    }

    private String statusSnapshot(String snapshotId) {
        return jdbcTemplate.queryForObject(
                "SELECT status_execucao FROM pdor_snapshot WHERE id = ?",
                String.class,
                snapshotId
        );
    }

    private static final class BarrierSnapshotRepository
            extends PdorSnapshotRepository {

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
        public java.util.Optional<PdorSnapshot> findByIdempotencyKey(
                String idempotencyKey
        ) {
            java.util.Optional<PdorSnapshot> snapshot =
                    super.findByIdempotencyKey(idempotencyKey);
            if (snapshot.isEmpty()
                    && emptyFinds.incrementAndGet() <= participants) {
                awaitBothRequestsAfterEmptyRead();
            }
            return snapshot;
        }

        @Override
        public void insert(PdorSnapshot snapshot) throws DuplicateKeyException {
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

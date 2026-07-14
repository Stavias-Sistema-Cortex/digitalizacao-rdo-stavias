package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = PdorController.class,
        properties = {
                "debug=false",
                "logging.level.root=INFO",
                "logging.level.org.springframework=INFO"
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        PdorApplicationService.class,
        PdorExceptionHandler.class
})
class PdorControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ObraRepository obraRepository;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private FinancialAccessService financialAccessService;

    @MockBean
    private PdorInputLoader inputLoader;

    @MockBean
    private PdorSnapshotRepository snapshotRepository;

    @MockBean
    private CortexOperationalMemoryService memoryService;

    private Obra obra;

    @BeforeEach
    void setUp() {
        obra = Obra.criar(
                "CW38386",
                "CW38386",
                "4ª Intervenção",
                "4ª Intervenção",
                "Intervias",
                null,
                "Leme",
                "SP",
                "SP 330",
                "ATIVA",
                "TESTE",
                null,
                null
        );

        when(obraRepository.findAtivasByIdentificador("CW38386"))
                .thenReturn(List.of(obra));
    }

    @Test
    void shouldReturn200ForInsufficientDataCalculationAsJson() throws Exception {
        when(inputLoader.load(any(Obra.class), nullable(LocalDate.class)))
                .thenReturn(insufficientBundle());
        when(snapshotRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/obras/{obraId}/pdor/calcular", "CW38386"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusExecucao").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.statusExecucaoLabel").value("Dados insuficientes"))
                .andExpect(jsonPath("$.tipoDisparo").value("MANUAL"))
                .andExpect(jsonPath("$.tipoDisparoLabel").value("Manual"))
                .andExpect(jsonPath("$.dataReferencia").value("2026-06-08"))
                .andExpect(jsonPath("$.obra.codigoContrato").value("CW38386"))
                .andExpect(jsonPath("$.p10").value(nullValue()))
                .andExpect(jsonPath("$.p50").value(nullValue()))
                .andExpect(jsonPath("$.p80").value(nullValue()))
                .andExpect(jsonPath("$.p95").value(nullValue()))
                .andExpect(jsonPath("$.receitaEstimadaFinal").value(nullValue()))
                .andExpect(jsonPath("$.racs.rci").value(nullValue()))
                .andExpect(jsonPath("$.racs.rciSpi").value(nullValue()))
                .andExpect(jsonPath("$.racs.bottomUp").value(nullValue()))
                .andExpect(jsonPath("$.racs.ponderado").value(nullValue()))
                .andExpect(jsonPath("$.probabilidadeAbaixoContrato").value(nullValue()))
                .andExpect(jsonPath("$.probabilidadeAbaixo95Pct").value(nullValue()))
                .andExpect(jsonPath("$.probabilidadeAbaixo90Pct").value(nullValue()))
                .andExpect(jsonPath("$.scoreHeuristico").value(nullValue()))
                .andExpect(jsonPath("$.confianca").value(nullValue()))
                .andExpect(jsonPath("$.simulacaoConvergiu").value(nullValue()))
                .andExpect(jsonPath("$.iteracoesSimulacao").value(nullValue()))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings[0]").value(containsString("Orçamento total aprovado ausente")))
                .andExpect(jsonPath("$.erroExecucao").value(containsString("Dados insuficientes para calcular o PDOR")))
                .andExpect(jsonPath("$.origemDados.contractValue.label").value("Orçamento total aprovado"))
                .andExpect(jsonPath("$.origemDados.contractValue.availability").value("ABSENT"))
                .andExpect(jsonPath("$.origemDados.contractValue.availabilityLabel").value("Ausente"))
                .andExpect(jsonPath("$.inputs.contractValue").value(nullValue()))
                .andExpect(jsonPath("$.inputs.measuredRevenue").value(nullValue()))
                .andExpect(jsonPath("$.inputs.validatedRevenue").value(nullValue()))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.obraId").doesNotExist())
                .andExpect(jsonPath("$.codigoObra").doesNotExist())
                .andExpect(jsonPath("$.inputOrigins").doesNotExist())
                .andExpect(jsonPath("$.executionStatus").doesNotExist())
                .andExpect(jsonPath("$.triggerType").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());

        ArgumentCaptor<PdorSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(PdorSnapshot.class);
        verify(snapshotRepository).insert(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().executionStatus())
                .isEqualTo(PdorExecutionStatus.INSUFFICIENT_DATA);
        assertThat(snapshotCaptor.getValue().revenueP50()).isNull();
    }

    @Test
    void shouldReturn404ForMissingWorksite() throws Exception {
        when(obraRepository.findAtivasByIdentificador("NAO-EXISTE"))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/obras/{obraId}/pdor/calcular", "NAO-EXISTE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.mensagem").value("Obra não encontrada: NAO-EXISTE"))
                .andExpect(jsonPath("$.caminho").value("/api/obras/NAO-EXISTE/pdor/calcular"));
    }

    @Test
    void shouldReturn409ForAmbiguousIdentifier() throws Exception {
        Obra outra = Obra.criar(
                "CW-OUTRA",
                "CW38386",
                null,
                "Outra obra",
                "Intervias",
                null,
                null,
                null,
                null,
                "ATIVA",
                "TESTE",
                null,
                null
        );
        when(obraRepository.findAtivasByIdentificador("AMBIGUA"))
                .thenReturn(List.of(obra, outra));

        mockMvc.perform(post("/api/obras/{obraId}/pdor/calcular", "AMBIGUA"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.mensagem").value("Identificador de obra ambíguo: AMBIGUA"));
    }

    @Test
    void shouldReturn400ForInvalidTriggerType() throws Exception {
        mockMvc.perform(post("/api/obras/{obraId}/pdor/calcular", "CW38386")
                        .param("tipoDisparo", "invalido"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensagem")
                        .value("tipoDisparo inválido. Use MANUAL, EVENT, SCHEDULED ou API."));
    }

    @Test
    void shouldValidateHistoryPageAndSize() throws Exception {
        mockMvc.perform(get("/api/obras/{obraId}/pdor/historico", "CW38386")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensagem").value("page não pode ser negativo."));

        mockMvc.perform(get("/api/obras/{obraId}/pdor/historico", "CW38386")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensagem").value("size deve estar entre 1 e 100."));

        mockMvc.perform(get("/api/obras/{obraId}/pdor/historico", "CW38386")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensagem").value("size deve estar entre 1 e 100."));
    }

    @Test
    void shouldSerializeCurrentSnapshot() throws Exception {
        PdorSnapshot snapshot = insufficientSnapshot(
                "snapshot-atual",
                LocalDateTime.of(2026, 6, 22, 11, 0)
        );
        when(snapshotRepository.findLatestByObraId(eq(obra.getId())))
                .thenReturn(Optional.of(snapshot));

        mockMvc.perform(get("/api/obras/{obraId}/pdor/atual", "CW38386"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("snapshot-atual"))
                .andExpect(jsonPath("$.snapshotExistente").value(true))
                .andExpect(jsonPath("$.statusExecucaoLabel").value("Dados insuficientes"))
                .andExpect(jsonPath("$.obra.nome").value("4ª Intervenção"))
                .andExpect(jsonPath("$.drivers").isArray())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.origemDados").isMap())
                .andExpect(jsonPath("$.inputs").isMap());
    }

    @Test
    void shouldSerializeOrderedHistory() throws Exception {
        PdorSnapshot newer = insufficientSnapshot(
                "snapshot-mais-recente",
                LocalDateTime.of(2026, 6, 22, 12, 0)
        );
        PdorSnapshot older = insufficientSnapshot(
                "snapshot-mais-antigo",
                LocalDateTime.of(2026, 6, 22, 10, 0)
        );

        when(snapshotRepository.countByObraId(eq(obra.getId()))).thenReturn(2L);
        when(snapshotRepository.findHistoryByObraId(eq(obra.getId()), eq(0), eq(2)))
                .thenReturn(List.of(newer, older));

        mockMvc.perform(get("/api/obras/{obraId}/pdor/historico", "CW38386")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value("snapshot-mais-recente"))
                .andExpect(jsonPath("$.items[1].id").value("snapshot-mais-antigo"))
                .andExpect(jsonPath("$.items[0].statusExecucaoLabel").value("Dados insuficientes"));
    }

    private PdorInputBundle insufficientBundle() {
        LocalDate referenceDate = LocalDate.of(2026, 6, 8);
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, PdorInputOrigin> origins = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        put(inputs, origins, missing, "contractValue", "Orçamento total aprovado", null, true);
        put(inputs, origins, missing, "measuredRevenue", "Custo realizado", null, true);
        put(inputs, origins, missing, "validatedRevenue", "Custo comprometido", null, true);
        put(inputs, origins, missing, "totalPlannedQuantity", "Quantidade total planejada", new BigDecimal("1000.000"), true);
        put(inputs, origins, missing, "plannedExecutedQuantity", "Quantidade planejada até a data de referência", new BigDecimal("800.000"), true);
        put(inputs, origins, missing, "actualExecutedQuantity", "Quantidade executada real", null, true);
        inputs.put("referenceDate", referenceDate);
        inputs.put("quantityMetric", "AREA_M2");
        inputs.put("programacaoRows", 172);
        inputs.put("rdoRows", 0);
        inputs.put("scheduleStartDate", LocalDate.of(2025, 12, 10));
        inputs.put("scheduleEndDate", referenceDate);

        return new PdorInputBundle(
                obra.getId(),
                "CW38386",
                referenceDate,
                inputs,
                origins,
                List.of(
                        "Orçamento total aprovado ausente; o PDOR não será calculado sem esse dado financeiro.",
                        "Custo realizado ausente; o PDOR não será calculado sem esse dado financeiro.",
                        "Custo comprometido ausente; o PDOR não usará estimativas financeiras substitutas."
                ),
                missing,
                new PdorInputBundle.SourceValues(
                        null,
                        null,
                        null,
                        1000.0,
                        800.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        0,
                        0,
                        0,
                        168,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        10_000
                ),
                com.projeto.cortex.intelligence.PdorEngine.HistoricalSeries.EMPTY
        );
    }

    private PdorSnapshot insufficientSnapshot(
            String id,
            LocalDateTime executedAt
    ) {
        return new PdorSnapshot(
                id,
                obra.getId(),
                "CW38386",
                executedAt,
                LocalDate.of(2026, 6, 8),
                "PDOR-0.2.0",
                "PDOR-ASSUMPTIONS-0.2.0",
                PdorExecutionStatus.INSUFFICIENT_DATA,
                PdorTriggerType.MANUAL,
                null,
                "a".repeat(64),
                objectMapper.valueToTree(insufficientBundle().inputs()),
                objectMapper.valueToTree(insufficientBundle().originsAsMap()),
                objectMapper.valueToTree(insufficientBundle().warnings()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper.createArrayNode(),
                "Dados insuficientes para calcular o PDOR.",
                executedAt
        );
    }

    private void put(
            Map<String, Object> inputs,
            Map<String, PdorInputOrigin> origins,
            List<String> missing,
            String field,
            String label,
            Object value,
            boolean required
    ) {
        inputs.put(field, value);
        PdorDataAvailability availability = value == null
                ? PdorDataAvailability.ABSENT
                : PdorDataAvailability.DERIVED;
        origins.put(
                field,
                new PdorInputOrigin(
                        field,
                        label,
                        availability,
                        value,
                        "teste-http",
                        value == null ? "Dado ausente no cenário de teste." : "Dado serializado no cenário de teste.",
                        required
                )
        );
        if (required && value == null) {
            missing.add(field);
        }
    }
}

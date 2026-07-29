package com.projeto.cortex.pdor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.projeto.cortex.intelligence.PdorEngine;
import com.projeto.cortex.intelligence.PdorContextBuilder;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdorApplicationServiceTest {

    private ObjectMapper objectMapper;
    private Obra obra;
    private ObraRepository obraRepository;
    private MutableInputLoader inputLoader;
    private InMemorySnapshotRepository snapshotRepository;
    private CortexOperationalMemoryService memoryService;
    private ObraOperabilityGuard operabilityGuard;
    private PdorApplicationService service;

    @BeforeEach
    void setUp() {
        objectMapper = objectMapper();
        obra = Obra.criar(
                "CW38386",
                null,
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

        obraRepository = mock(ObraRepository.class);
        inputLoader = new MutableInputLoader(validBundle(obra, "350000.00"));
        snapshotRepository = new InMemorySnapshotRepository(objectMapper);
        memoryService = mock(CortexOperationalMemoryService.class);
        operabilityGuard = mock(ObraOperabilityGuard.class);
        service = new PdorApplicationService(
                obraRepository,
                inputLoader,
                snapshotRepository,
                objectMapper,
                memoryService,
                operabilityGuard
        );

        when(obraRepository.findByIdentificador("CW38386"))
                .thenReturn(List.of(obra));
        when(obraRepository.findByIdentificador(obra.getId()))
                .thenReturn(List.of(obra));
    }

    @Test
    void shouldCreateValidSnapshot() {
        PdorResultadoResponse response =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        assertThat(response.statusExecucao()).isEqualTo("SUCCESS");
        assertThat(response.statusExecucaoLabel()).isEqualTo("Concluído");
        assertThat(response.snapshotExistente()).isFalse();
        assertThat(response.executedAtUtc().getNano() % 1_000).isZero();
        assertThat(response.receitaEstimadaFinal()).isNotNull();
        assertThat(response.p50()).isNotNull();
        assertThat(response.racs()).containsKeys(
                "rci",
                "rciSpi",
                "bottomUp",
                "ponderado"
        );
        assertThat(response.drivers().isArray()).isTrue();
        assertThat(response.warnings().isArray()).isTrue();
        assertThat(response.inputs().isObject()).isTrue();
        assertThat(response.versaoDados()).hasSize(64);
        assertThat(response.escopoAnalisado().path("obraId").asText())
                .isEqualTo(obra.getId());
        assertThat(response.janelaTemporal().path("dataReferencia").asText())
                .isEqualTo("2026-06-08");
        assertThat(response.featuresUtilizadas().isArray()).isTrue();
        assertThat(response.featuresUtilizadas().toString())
                .contains("contractValue", "measuredRevenue");
        assertThat(response.dadosAusentes().isArray()).isTrue();
        assertThat(response.limitacoes().isArray()).isTrue();
        assertThat(response.alertasDerivados().isArray()).isTrue();
        assertThat(response.recomendacoes().isArray()).isTrue();
        assertThat(response.comparacaoAnterior().path("disponivel").asBoolean())
                .isFalse();
        assertThat(response.evidencias().isArray()).isTrue();
        assertThat(response.tipoIniciador()).isEqualTo("PROCESS");
        assertThat(response.iniciadoPor()).isEqualTo("PROCESSO_PDOR");
        assertThat(snapshotRepository.size()).isEqualTo(1);
    }

    @Test
    void snapshotDeProcessoRegistraOEventoPdorSemAtribuirUmUsuario() {
        service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        verify(memoryService).registrarObjeto(
                eq("OBRA"),
                eq(obra.getId()),
                any(),
                eq(obra.getNome()),
                eq("ATIVA"),
                eq("PDOR")
        );
        verify(memoryService).registrarObjeto(
                eq("PDOR"),
                any(String.class),
                any(),
                any(),
                eq("SUCCESS"),
                eq("PDOR")
        );
        verify(memoryService).registrarRelacaoAtiva(
                eq("PDOR"),
                any(String.class),
                eq("OBRA"),
                eq(obra.getId()),
                eq("ANALISA"),
                eq("PDOR"),
                any()
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload =
                ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> related =
                ArgumentCaptor.forClass(List.class);
        verify(memoryService).registrarEventoAuditado(
                any(String.class),
                eq("PDOR"),
                any(String.class),
                eq("PDOR_CALCULADO"),
                eq("PDOR"),
                eq(obra.getId()),
                isNull(),
                isNull(),
                related.capture(),
                eq("ONLINE"),
                eq("SYNCED"),
                any(),
                any(),
                eq(2),
                payload.capture(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(Map.of()),
                eq(Map.of()),
                eq("SUCESSO"),
                isNull()
        );

        assertThat(related.getValue())
                .anySatisfy(entidade -> {
                    assertThat(entidade.get("tipo")).isEqualTo("OBRA");
                    assertThat(entidade.get("id")).isEqualTo(obra.getId());
                });
        assertThat(payload.getValue().get("obraId")).isEqualTo(obra.getId());
        assertThat(payload.getValue().get("receitaEstimadaFinal")).isNotNull();
        assertThat(payload.getValue().get("p50Receita")).isNotNull();
        assertThat(payload.getValue().get("probabilidadeAbaixoContrato")).isNotNull();

        verify(memoryService).registrarEvidencias(
                eq("PDOR"),
                any(String.class),
                eq("PDOR"),
                any()
        );
        verify(memoryService).registrarEvidencias(
                eq("OBRA"),
                eq(obra.getId()),
                eq("PDOR"),
                any()
        );
    }

    @Test
    void shouldCreateInsufficientDataSnapshotWithoutSilentFinancialZero() {
        inputLoader.bundle = insufficientBundle(obra);

        PdorResultadoResponse response =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        assertThat(response.statusExecucao()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.statusExecucaoLabel()).isEqualTo("Dados insuficientes");
        assertThat(response.erroExecucao())
                .contains("Dados insuficientes para calcular o PDOR");
        assertThat(response.p10()).isNull();
        assertThat(response.p50()).isNull();
        assertThat(response.p80()).isNull();
        assertThat(response.p95()).isNull();
        assertThat(response.probabilidadeAbaixoContrato()).isNull();
        assertThat(response.probabilidadeAbaixo95Pct()).isNull();
        assertThat(response.probabilidadeAbaixo90Pct()).isNull();
        assertThat(response.racs()).allSatisfy((name, value) -> assertThat(value).isNull());
        assertThat(response.inputs().get("contractValue").isNull()).isTrue();
        assertThat(response.inputs().get("measuredRevenue").isNull()).isTrue();
        assertThat(response.inputs().get("validatedRevenue").isNull()).isTrue();
        assertThat(response.warnings().toString()).contains("Orçamento total aprovado ausente");
        assertThat(response.origemDados().get("contractValue").get("availability").asText())
                .isEqualTo("ABSENT");
    }

    @Test
    void calculationFailureMarksPreviousSnapshotStaleAndReturnsCorrelationOnly() {
        PdorResultadoResponse previous =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        inputLoader.bundle = validBundle(obra, "390000.00");

        PdorEngine failingEngine = mock(PdorEngine.class);
        when(failingEngine.calculate(any(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host"));
        PdorApplicationService failingService = new PdorApplicationService(
                obraRepository,
                inputLoader,
                snapshotRepository,
                objectMapper,
                memoryService,
                new PdorContextBuilder(),
                failingEngine,
                operabilityGuard
        );

        Logger logger = (Logger) LoggerFactory.getLogger(
                PdorApplicationService.class
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        PdorCalculationException failure;
        try {
            failure = catchThrowableOfType(
                    () -> failingService.calcular(
                            "CW38386", null, PdorTriggerType.API, null
                    ),
                    PdorCalculationException.class
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(failure.correlationId())
                .isEqualTo(snapshotRepository.lastFailureCorrelationId)
                .matches("[0-9a-f-]{36}");
        assertThat(failure.getMessage()).isEqualTo("PDOR_CALCULATION_FAILED");
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("secret-host"));
        assertThat(appender.list)
                .extracting(ILoggingEvent::getThrowableProxy)
                .containsOnlyNulls();
        assertThat(snapshotRepository.size()).isEqualTo(1);
        PdorSnapshot stored = snapshotRepository.findById(previous.id())
                .orElseThrow();
        assertThat(stored.stale()).isTrue();
        assertThat(stored.current()).isFalse();
        assertThat(snapshotRepository.findCurrentByObraId(obra.getId()))
                .isEmpty();
    }

    @Test
    void shouldReturnExistingSnapshotForSameInput() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        PdorResultadoResponse second =
                service.calcular("CW38386", null, PdorTriggerType.API, "evento-1");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.snapshotExistente()).isTrue();
        assertThat(snapshotRepository.size()).isEqualTo(1);
    }

    @Test
    void archivedWorksiteKeepsExactSnapshotReplayAvailable() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        obra.arquivar();
        when(obraRepository.findByIdentificador("CW38386"))
                .thenReturn(List.of(obra));
        doThrow(archivedWorksite()).when(operabilityGuard)
                .requireWritable(obra.getId());

        PdorResultadoResponse replay =
                service.calcular("CW38386", null, PdorTriggerType.API, "evento-1");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.snapshotExistente()).isTrue();
        assertThat(snapshotRepository.size()).isEqualTo(1);
    }

    @Test
    void archivedWorksiteRejectsNewSnapshotWithoutChangingHistory() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        obra.arquivar();
        inputLoader.bundle = validBundle(obra, "390000.00");
        doThrow(archivedWorksite()).when(operabilityGuard)
                .requireWritable(obra.getId());

        assertThatThrownBy(() ->
                service.calcular("CW38386", null, PdorTriggerType.API, "evento-2")
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        assertThat(snapshotRepository.size()).isEqualTo(1);
        assertThat(snapshotRepository.findCurrentByObraId(obra.getId()))
                .map(PdorSnapshot::id)
                .contains(first.id());
    }

    @Test
    void archivedWorksiteRejectsFailureAuditWithoutStalingCurrentSnapshot() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        obra.arquivar();
        inputLoader.bundle = validBundle(obra, "390000.00");
        PdorEngine failingEngine = mock(PdorEngine.class);
        when(failingEngine.calculate(any(), any()))
                .thenThrow(new IllegalStateException("failure"));
        PdorApplicationService failingService = new PdorApplicationService(
                obraRepository,
                inputLoader,
                snapshotRepository,
                objectMapper,
                memoryService,
                new PdorContextBuilder(),
                failingEngine,
                operabilityGuard
        );
        doThrow(archivedWorksite()).when(operabilityGuard)
                .requireWritable(obra.getId());

        assertThatThrownBy(() ->
                failingService.calcular(
                        "CW38386", null, PdorTriggerType.API, "evento-2"
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        assertThat(snapshotRepository.findCurrentByObraId(obra.getId()))
                .map(PdorSnapshot::id)
                .contains(first.id());
    }

    @Test
    void shouldRepairOntologyPublicationWhenIdempotentSnapshotAlreadyExists() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        clearInvocations(memoryService);

        PdorResultadoResponse reused =
                service.calcular("CW38386", null, PdorTriggerType.API, "evento-1");

        assertThat(reused.id()).isEqualTo(first.id());
        assertThat(reused.snapshotExistente()).isTrue();
        verify(memoryService).registrarEventoAuditado(
                eq(first.id()),
                eq("PDOR"),
                eq(first.id()),
                eq("PDOR_CALCULADO"),
                eq("PDOR"),
                eq(obra.getId()),
                isNull(),
                isNull(),
                any(),
                eq("ONLINE"),
                eq("SYNCED"),
                any(),
                any(),
                eq(2),
                any(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(Map.of()),
                eq(Map.of()),
                eq("SUCESSO"),
                isNull()
        );
    }

    @Test
    void shouldCreateNewSnapshotWhenInputChangesAndKeepHistoryImmutable() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        inputLoader.bundle = validBundle(obra, "390000.00");
        PdorResultadoResponse second =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.comparacaoAnterior().path("disponivel").asBoolean())
                .isTrue();
        assertThat(second.comparacaoAnterior().path("snapshotAnteriorId").asText())
                .isEqualTo(first.id());
        assertThat(second.comparacaoAnterior().path("inputsAlterados").toString())
                .contains("measuredRevenue");
        assertThat(snapshotRepository.size()).isEqualTo(2);
        assertThat(snapshotRepository.findById(first.id())
                .orElseThrow()
                .inputs()
                .get("measuredRevenue")
                .decimalValue())
                .isEqualByComparingTo(new BigDecimal("350000.00"));
        assertThat(snapshotRepository.findById(second.id())
                .orElseThrow()
                .inputs()
                .get("measuredRevenue")
                .decimalValue())
                .isEqualByComparingTo(new BigDecimal("390000.00"));
    }

    @Test
    void shouldRecoverExistingSnapshotWhenUniqueConstraintWinsRace() {
        snapshotRepository.duplicateNextInsert = true;

        PdorResultadoResponse response =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        assertThat(response.statusExecucao()).isEqualTo("SUCCESS");
        assertThat(response.snapshotExistente()).isTrue();
        assertThat(snapshotRepository.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnNotFoundForMissingWorksite() {
        when(obraRepository.findByIdentificador("NAO-EXISTE"))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                service.calcular("NAO-EXISTE", null, PdorTriggerType.MANUAL, null)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("Obra não encontrada");
    }

    @Test
    void shouldAcceptUuidAndCodeIdentifiers() {
        PdorResultadoResponse byCode =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        PdorResultadoResponse byUuid =
                service.calcular(obra.getId(), null, PdorTriggerType.MANUAL, null);

        assertThat(byUuid.id()).isEqualTo(byCode.id());
        assertThat(byUuid.obra().id()).isEqualTo(obra.getId());
    }

    @Test
    void shouldRejectAmbiguousIdentifier() {
        Obra outra = Obra.criar(
                "CW-OUTRA",
                "CW38386",
                null,
                "Outra obra",
                null,
                null,
                null,
                null,
                null,
                "ATIVA",
                "TESTE",
                null,
                null
        );
        when(obraRepository.findByIdentificador("AMBIGUA"))
                .thenReturn(List.of(obra, outra));

        assertThatThrownBy(() ->
                service.calcular("AMBIGUA", null, PdorTriggerType.MANUAL, null)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT")
                .hasMessageContaining("Identificador de obra ambíguo");
    }

    @Test
    void shouldReturnCurrentAndPagedHistoryOrderedByExecutionDate() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        inputLoader.bundle = validBundle(obra, "390000.00");
        PdorResultadoResponse second =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        inputLoader.bundle = validBundle(obra, "410000.00");
        PdorResultadoResponse third =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        assertThat(service.buscarAtual("CW38386").id()).isEqualTo(third.id());

        PdorHistoricoResponse page = service.buscarHistorico("CW38386", 0, 2);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).extracting(PdorResultadoResponse::id)
                .containsExactly(third.id(), second.id());
        assertThat(page.items()).extracting(PdorResultadoResponse::id)
                .doesNotContain(first.id());
    }

    @Test
    void archivedWorksiteKeepsCurrentAndHistoryReadable() {
        PdorResultadoResponse current =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);
        obra.arquivar();
        when(obraRepository.findByIdentificador("CW38386"))
                .thenReturn(List.of(obra));

        assertThat(service.buscarAtual("CW38386").id()).isEqualTo(current.id());
        assertThat(service.buscarHistorico("CW38386", 0, 10).items())
                .extracting(PdorResultadoResponse::id)
                .containsExactly(current.id());
    }

    @Test
    void shouldReturnNullOnlyWhenKnownWorksiteHasNoCurrentSnapshot() {
        assertThat(service.buscarAtualSeExistente("CW38386")).isNull();

        assertThatThrownBy(() ->
                service.buscarAtualSeExistente("UNKNOWN-WORKSITE")
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("Obra não encontrada");
    }

    @Test
    void shouldUseCanonicalIdempotencyPayload() {
        PdorInputBundle first = bundleWithInputOrder(
                obra,
                List.of("measuredRevenue", "contractValue"),
                "350000.00",
                "1000000.0"
        );
        PdorInputBundle second = bundleWithInputOrder(
                obra,
                List.of("contractValue", "measuredRevenue"),
                "350000.0",
                "1000000.00"
        );
        PdorInputBundle changed = bundleWithInputOrder(
                obra,
                List.of("contractValue", "measuredRevenue"),
                "360000.00",
                "1000000.00"
        );

        assertThat(service.calculateIdempotencyKey(second))
                .isEqualTo(service.calculateIdempotencyKey(first));
        assertThat(service.calculateIdempotencyKey(changed))
                .isNotEqualTo(service.calculateIdempotencyKey(first));

        Map<String, Object> payload = service.idempotencyPayload(first);

        assertThat(payload).containsKeys(
                "modelVersion",
                "algorithmVersion",
                "assumptionsVersion",
                "inputs",
                "inputAvailability",
                "missingRequiredFields"
        );
        assertThat(payload).doesNotContainKeys("executadoEm", "criadoEm", "timestamp");
        assertThat(payload.get("modelVersion")).isEqualTo(PdorEngine.MODEL_VERSION);
        assertThat(payload.get("algorithmVersion"))
                .isEqualTo(PdorApplicationService.REVENUE_ALGORITHM_VERSION);
        assertThat(payload.get("assumptionsVersion"))
                .isEqualTo(PdorEngine.ASSUMPTIONS_VERSION);
    }

    @Test
    void controllerMethodsShouldNotExposePersistenceEntity() throws Exception {
        assertThat(PdorController.class
                .getMethod(
                        "calcular",
                        String.class,
                        LocalDate.class,
                        String.class,
                        String.class
                )
                .getReturnType())
                .isEqualTo(PdorResultadoResponse.class);
        assertThat(PdorController.class
                .getMethod("atual", String.class)
                .getReturnType())
                .isEqualTo(PdorResultadoResponse.class);
        assertThat(PdorController.class
                .getMethod("historico", String.class, int.class, int.class)
                .getReturnType())
                .isEqualTo(PdorHistoricoResponse.class);
    }

    private static PdorInputBundle validBundle(Obra obra, String measuredRevenue) {
        LocalDate referenceDate = LocalDate.of(2026, 6, 8);
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, PdorInputOrigin> origins = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        put(inputs, origins, missing, "contractValue", new BigDecimal("1000000.00"), true);
        put(inputs, origins, missing, "measuredRevenue", new BigDecimal(measuredRevenue), true);
        put(inputs, origins, missing, "validatedRevenue", new BigDecimal("420000.00"), true);
        put(inputs, origins, missing, "totalPlannedQuantity", new BigDecimal("1000.000"), true);
        put(inputs, origins, missing, "plannedExecutedQuantity", new BigDecimal("500.000"), true);
        put(inputs, origins, missing, "actualExecutedQuantity", new BigDecimal("460.000"), true);
        put(inputs, origins, missing, "expectedMaterialConsumption", new BigDecimal("100.000"), false);
        put(inputs, origins, missing, "actualMaterialConsumption", new BigDecimal("104.000"), false);
        put(inputs, origins, missing, "expectedProductivity", new BigDecimal("10.000"), false);
        put(inputs, origins, missing, "actualProductivity", new BigDecimal("9.200"), false);
        put(inputs, origins, missing, "equipmentDowntimeHours30d", new BigDecimal("2.000"), false);
        put(inputs, origins, missing, "plannedEquipmentHours30d", new BigDecimal("120.000"), false);
        put(inputs, origins, missing, "delayedRdos", 1, false);
        put(inputs, origins, missing, "criticalOccurrences", 0, false);
        put(inputs, origins, missing, "pendingSyncEvents", 0, false);
        put(inputs, origins, missing, "hoursSinceLastSync", 4, false);
        inputs.put("referenceDate", referenceDate);
        inputs.put("quantityMetric", "AREA_M2");
        inputs.put("programacaoRows", 172);
        inputs.put("rdoRows", 10);
        inputs.put("scheduleStartDate", LocalDate.of(2025, 12, 10));
        inputs.put("scheduleEndDate", referenceDate);

        return new PdorInputBundle(
                obra.getId(),
                obra.getCodigoContrato(),
                referenceDate,
                inputs,
                origins,
                List.of("Há 25 linhas de programação sem quantidade completa."),
                missing,
                new PdorInputBundle.SourceValues(
                        new BigDecimal("1000000.00"),
                        new BigDecimal(measuredRevenue),
                        new BigDecimal("420000.00"),
                        1000.0,
                        500.0,
                        460.0,
                        100.0,
                        104.0,
                        10.0,
                        9.2,
                        2.0,
                        120.0,
                        1,
                        0,
                        0,
                        4,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        2_000
                ),
                PdorEngine.HistoricalSeries.EMPTY
        );
    }

    private static PdorInputBundle insufficientBundle(Obra obra) {
        PdorInputBundle valid = validBundle(obra, "350000.00");
        Map<String, Object> inputs = new LinkedHashMap<>(valid.inputs());
        Map<String, PdorInputOrigin> origins = new LinkedHashMap<>(valid.origins());
        List<String> missing = new ArrayList<>();

        putAbsent(inputs, origins, missing, "contractValue", true);
        putAbsent(inputs, origins, missing, "measuredRevenue", true);
        putAbsent(inputs, origins, missing, "validatedRevenue", true);

        return new PdorInputBundle(
                obra.getId(),
                obra.getCodigoContrato(),
                valid.referenceDate(),
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
                        500.0,
                        460.0,
                        100.0,
                        104.0,
                        10.0,
                        9.2,
                        2.0,
                        120.0,
                        1,
                        0,
                        0,
                        4,
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true,
                        true,
                        2_000
                ),
                PdorEngine.HistoricalSeries.EMPTY
        );
    }

    private static PdorInputBundle bundleWithInputOrder(
            Obra obra,
            List<String> fieldOrder,
            String measuredRevenue,
            String contractValue
    ) {
        PdorInputBundle valid = validBundle(obra, measuredRevenue);
        Map<String, Object> inputs = new LinkedHashMap<>();
        fieldOrder.forEach(field -> {
            if ("measuredRevenue".equals(field)) {
                inputs.put(field, new BigDecimal(measuredRevenue));
            }
            if ("contractValue".equals(field)) {
                inputs.put(field, new BigDecimal(contractValue));
            }
        });
        valid.inputs().forEach(inputs::putIfAbsent);
        return new PdorInputBundle(
                valid.obraId(),
                valid.codigoObra(),
                valid.referenceDate(),
                inputs,
                valid.origins(),
                valid.warnings(),
                List.of("validatedRevenue", "measuredRevenue", "contractValue"),
                valid.sourceValues(),
                valid.historicalSeries()
        );
    }

    private static void put(
            Map<String, Object> inputs,
            Map<String, PdorInputOrigin> origins,
            List<String> missing,
            String field,
            Object value,
            boolean required
    ) {
        inputs.put(field, value);
        origins.put(
                field,
                new PdorInputOrigin(
                        field,
                        field,
                        value == null ? PdorDataAvailability.ABSENT : PdorDataAvailability.DIRECT,
                        value,
                        "teste",
                        "teste",
                        required
                )
        );
        if (required && value == null) {
            missing.add(field);
        }
    }

    private static void putAbsent(
            Map<String, Object> inputs,
            Map<String, PdorInputOrigin> origins,
            List<String> missing,
            String field,
            boolean required
    ) {
        inputs.put(field, null);
        origins.put(
                field,
                new PdorInputOrigin(
                        field,
                        field,
                        PdorDataAvailability.ABSENT,
                        null,
                        "teste",
                        "ausente",
                        required
                )
        );
        missing.add(field);
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static ResponseStatusException archivedWorksite() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        );
    }

    private static final class MutableInputLoader implements PdorInputLoader {
        private PdorInputBundle bundle;

        private MutableInputLoader(PdorInputBundle bundle) {
            this.bundle = bundle;
        }

        @Override
        public PdorInputBundle load(Obra obra, LocalDate requestedReferenceDate) {
            return bundle;
        }
    }

    private static final class InMemorySnapshotRepository
            extends PdorSnapshotRepository {

        private final List<PdorSnapshot> snapshots = new ArrayList<>();
        private final Map<String, PdorSnapshot> byIdempotencyKey = new LinkedHashMap<>();
        private final LocalDateTime baseTime = LocalDateTime.of(2026, 6, 22, 10, 0);
        private boolean duplicateNextInsert;
        private String lastFailureCorrelationId;

        private InMemorySnapshotRepository(ObjectMapper objectMapper) {
            super(null, objectMapper);
        }

        @Override
        public void insert(PdorSnapshot snapshot) throws DuplicateKeyException {
            PdorSnapshot stored = withSequentialTime(snapshot);
            if (duplicateNextInsert) {
                duplicateNextInsert = false;
                store(stored);
                throw new DuplicateKeyException("duplicate idempotency key");
            }
            if (byIdempotencyKey.containsKey(snapshot.idempotencyKey())) {
                throw new DuplicateKeyException("duplicate idempotency key");
            }
            store(stored);
        }

        @Override
        public void replaceCurrent(PdorSnapshot snapshot)
                throws DuplicateKeyException {
            snapshots.replaceAll(existing -> existing.current()
                    ? existing.withRevenueMetadata(
                            existing.algorithmVersion(),
                            existing.evidenceIds(),
                            existing.evidenceHighWaterMark(),
                            existing.coverageCode(),
                            existing.assumptions(),
                            existing.executedAtUtc(),
                            true,
                            false
                    )
                    : existing);
            rebuildIndex();
            insert(snapshot);
        }

        @Override
        public void recordFailureAndMarkCurrentStale(
                String correlationId,
                String obraId,
                String previousSnapshotId,
                Long evidenceHighWaterMark,
                PdorTriggerType triggerType,
                String initiatedBy,
                java.time.Instant attemptedAtUtc
        ) {
            lastFailureCorrelationId = correlationId;
            snapshots.replaceAll(existing -> existing.obraId().equals(obraId)
                    && existing.current()
                    ? existing.withRevenueMetadata(
                            existing.algorithmVersion(),
                            existing.evidenceIds(),
                            existing.evidenceHighWaterMark(),
                            existing.coverageCode(),
                            existing.assumptions(),
                            existing.executedAtUtc(),
                            true,
                            false
                    )
                    : existing);
            rebuildIndex();
        }

        @Override
        public Optional<PdorSnapshot> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
        }

        @Override
        public Optional<PdorSnapshot> findLatestByObraId(String obraId) {
            return sortedByLatest(obraId).stream().findFirst();
        }

        @Override
        public Optional<PdorSnapshot> findCurrentByObraId(String obraId) {
            return snapshots.stream()
                    .filter(snapshot -> snapshot.obraId().equals(obraId))
                    .filter(PdorSnapshot::current)
                    .findFirst();
        }

        @Override
        public List<PdorSnapshot> findHistoryByObraId(String obraId, int page, int size) {
            return sortedByLatest(obraId).stream()
                    .skip((long) page * size)
                    .limit(size)
                    .toList();
        }

        @Override
        public long countByObraId(String obraId) {
            return snapshots.stream()
                    .filter(snapshot -> snapshot.obraId().equals(obraId))
                    .count();
        }

        private int size() {
            return snapshots.size();
        }

        private Optional<PdorSnapshot> findById(String id) {
            return snapshots.stream()
                    .filter(snapshot -> snapshot.id().equals(id))
                    .findFirst();
        }

        private void store(PdorSnapshot snapshot) {
            snapshots.add(snapshot);
            byIdempotencyKey.put(snapshot.idempotencyKey(), snapshot);
        }

        private void rebuildIndex() {
            byIdempotencyKey.clear();
            snapshots.forEach(snapshot ->
                    byIdempotencyKey.put(snapshot.idempotencyKey(), snapshot));
        }

        private List<PdorSnapshot> sortedByLatest(String obraId) {
            return snapshots.stream()
                    .filter(snapshot -> snapshot.obraId().equals(obraId))
                    .sorted(Comparator
                            .comparing(PdorSnapshot::executedAt)
                            .thenComparing(PdorSnapshot::createdAt)
                            .thenComparing(PdorSnapshot::id)
                            .reversed())
                    .toList();
        }

        private PdorSnapshot withSequentialTime(PdorSnapshot snapshot) {
            LocalDateTime time = baseTime.plusSeconds(snapshots.size());
            PdorSnapshot stored = new PdorSnapshot(
                    snapshot.id(),
                    snapshot.obraId(),
                    snapshot.codigoObra(),
                    time,
                    snapshot.referenceDate(),
                    snapshot.modelVersion(),
                    snapshot.assumptionsVersion(),
                    snapshot.executionStatus(),
                    snapshot.triggerType(),
                    snapshot.originEventId(),
                    snapshot.idempotencyKey(),
                    snapshot.inputs(),
                    snapshot.inputOrigins(),
                    snapshot.warnings(),
                    snapshot.calculationMode(),
                    snapshot.calibrationStatus(),
                    snapshot.projectPhase(),
                    snapshot.riskLevel(),
                    snapshot.revenueP10(),
                    snapshot.revenueP50(),
                    snapshot.revenueP80(),
                    snapshot.revenueP95(),
                    snapshot.racRci(),
                    snapshot.racRciSpi(),
                    snapshot.racBottomUp(),
                    snapshot.racWeighted(),
                    snapshot.rci(),
                    snapshot.spi(),
                    snapshot.probabilityBelowContract(),
                    snapshot.probabilityBelow95Pct(),
                    snapshot.probabilityBelow90Pct(),
                    snapshot.heuristicRiskScore(),
                    snapshot.confidence(),
                    snapshot.simulationConverged(),
                    snapshot.simulationIterations(),
                    snapshot.drivers(),
                    snapshot.executionError(),
                    time,
                    snapshot.dataVersion(),
                    snapshot.analysisScope(),
                    snapshot.temporalWindow(),
                    snapshot.featuresUsed(),
                    snapshot.missingData(),
                    snapshot.limitations(),
                    snapshot.alerts(),
                    snapshot.recommendations(),
                    snapshot.previousComparison(),
                    snapshot.evidence(),
                    snapshot.initiatedBy(),
                    snapshot.initiatorType()
            );
            return stored.withRevenueMetadata(
                    snapshot.algorithmVersion(),
                    snapshot.evidenceIds(),
                    snapshot.evidenceHighWaterMark(),
                    snapshot.coverageCode(),
                    snapshot.assumptions(),
                    time.toInstant(java.time.ZoneOffset.UTC),
                    snapshot.stale(),
                    snapshot.current()
            );
        }
    }
}

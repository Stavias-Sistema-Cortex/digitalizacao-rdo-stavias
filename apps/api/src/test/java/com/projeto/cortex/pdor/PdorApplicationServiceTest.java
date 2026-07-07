package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.projeto.cortex.intelligence.PdorEngine;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdorApplicationServiceTest {

    private ObjectMapper objectMapper;
    private Obra obra;
    private ObraRepository obraRepository;
    private MutableInputLoader inputLoader;
    private InMemorySnapshotRepository snapshotRepository;
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
        service = new PdorApplicationService(
                obraRepository,
                inputLoader,
                snapshotRepository,
                objectMapper
        );

        when(obraRepository.findAtivasByIdentificador("CW38386"))
                .thenReturn(List.of(obra));
        when(obraRepository.findAtivasByIdentificador(obra.getId()))
                .thenReturn(List.of(obra));
    }

    @Test
    void shouldCreateValidSnapshot() {
        PdorResultadoResponse response =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        assertThat(response.statusExecucao()).isEqualTo("SUCCESS");
        assertThat(response.statusExecucaoLabel()).isEqualTo("Concluído");
        assertThat(response.snapshotExistente()).isFalse();
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
        assertThat(snapshotRepository.size()).isEqualTo(1);
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
        assertThat(response.inputs().get("approvedBudget").isNull()).isTrue();
        assertThat(response.inputs().get("actualCost").isNull()).isTrue();
        assertThat(response.inputs().get("committedCost").isNull()).isTrue();
        assertThat(response.warnings().toString()).contains("Orçamento total aprovado ausente");
        assertThat(response.origemDados().get("approvedBudget").get("availability").asText())
                .isEqualTo("ABSENT");
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
    void shouldCreateNewSnapshotWhenInputChangesAndKeepHistoryImmutable() {
        PdorResultadoResponse first =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        inputLoader.bundle = validBundle(obra, "390000.00");
        PdorResultadoResponse second =
                service.calcular("CW38386", null, PdorTriggerType.MANUAL, null);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(snapshotRepository.size()).isEqualTo(2);
        assertThat(snapshotRepository.findById(first.id())
                .orElseThrow()
                .inputs()
                .get("actualCost")
                .decimalValue())
                .isEqualByComparingTo(new BigDecimal("350000.00"));
        assertThat(snapshotRepository.findById(second.id())
                .orElseThrow()
                .inputs()
                .get("actualCost")
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
        when(obraRepository.findAtivasByIdentificador("NAO-EXISTE"))
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
        when(obraRepository.findAtivasByIdentificador("AMBIGUA"))
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
    void shouldUseCanonicalIdempotencyPayload() {
        PdorInputBundle first = bundleWithInputOrder(
                obra,
                List.of("actualCost", "approvedBudget"),
                "350000.00",
                "1000000.0"
        );
        PdorInputBundle second = bundleWithInputOrder(
                obra,
                List.of("approvedBudget", "actualCost"),
                "350000.0",
                "1000000.00"
        );
        PdorInputBundle changed = bundleWithInputOrder(
                obra,
                List.of("approvedBudget", "actualCost"),
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
                "assumptionsVersion",
                "inputs",
                "inputAvailability",
                "missingRequiredFields"
        );
        assertThat(payload).doesNotContainKeys("executadoEm", "criadoEm", "timestamp");
        assertThat(payload.get("modelVersion")).isEqualTo(PdorEngine.MODEL_VERSION);
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

    private static PdorInputBundle validBundle(Obra obra, String actualCost) {
        LocalDate referenceDate = LocalDate.of(2026, 6, 8);
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, PdorInputOrigin> origins = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        put(inputs, origins, missing, "approvedBudget", new BigDecimal("1000000.00"), true);
        put(inputs, origins, missing, "actualCost", new BigDecimal(actualCost), true);
        put(inputs, origins, missing, "committedCost", new BigDecimal("420000.00"), true);
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
                        new BigDecimal(actualCost),
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
                )
        );
    }

    private static PdorInputBundle insufficientBundle(Obra obra) {
        PdorInputBundle valid = validBundle(obra, "350000.00");
        Map<String, Object> inputs = new LinkedHashMap<>(valid.inputs());
        Map<String, PdorInputOrigin> origins = new LinkedHashMap<>(valid.origins());
        List<String> missing = new ArrayList<>();

        putAbsent(inputs, origins, missing, "approvedBudget", true);
        putAbsent(inputs, origins, missing, "actualCost", true);
        putAbsent(inputs, origins, missing, "committedCost", true);

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
                )
        );
    }

    private static PdorInputBundle bundleWithInputOrder(
            Obra obra,
            List<String> fieldOrder,
            String actualCost,
            String approvedBudget
    ) {
        PdorInputBundle valid = validBundle(obra, actualCost);
        Map<String, Object> inputs = new LinkedHashMap<>();
        fieldOrder.forEach(field -> {
            if ("actualCost".equals(field)) {
                inputs.put(field, new BigDecimal(actualCost));
            }
            if ("approvedBudget".equals(field)) {
                inputs.put(field, new BigDecimal(approvedBudget));
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
                List.of("committedCost", "actualCost", "approvedBudget"),
                valid.sourceValues()
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
        public Optional<PdorSnapshot> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
        }

        @Override
        public Optional<PdorSnapshot> findLatestByObraId(String obraId) {
            return sortedByLatest(obraId).stream().findFirst();
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
            return new PdorSnapshot(
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
                    snapshot.costP10(),
                    snapshot.costP50(),
                    snapshot.costP80(),
                    snapshot.costP95(),
                    snapshot.racRci(),
                    snapshot.racRciSpi(),
                    snapshot.racBottomUp(),
                    snapshot.racWeighted(),
                    snapshot.rci(),
                    snapshot.spi(),
                    snapshot.probabilityAnyOverrun(),
                    snapshot.probabilityOverFivePercent(),
                    snapshot.probabilityOverTenPercent(),
                    snapshot.heuristicRiskScore(),
                    snapshot.confidence(),
                    snapshot.simulationConverged(),
                    snapshot.simulationIterations(),
                    snapshot.drivers(),
                    snapshot.executionError(),
                    time
            );
        }
    }
}

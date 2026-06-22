package com.projeto.cortex.pdoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.projeto.cortex.intelligence.PdocEngine;
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

class PdocApplicationServiceTest {

    private ObjectMapper objectMapper;
    private Obra obra;
    private ObraRepository obraRepository;
    private MutableInputLoader inputLoader;
    private InMemorySnapshotRepository snapshotRepository;
    private PdocApplicationService service;

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
        service = new PdocApplicationService(
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
        PdocResultadoResponse response =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

        assertThat(response.statusExecucao()).isEqualTo("SUCCESS");
        assertThat(response.statusExecucaoLabel()).isEqualTo("Concluído");
        assertThat(response.snapshotExistente()).isFalse();
        assertThat(response.custoEstimadoFinal()).isNotNull();
        assertThat(response.p50()).isNotNull();
        assertThat(response.eacs()).containsKeys(
                "cpi",
                "cpiSpi",
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

        PdocResultadoResponse response =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

        assertThat(response.statusExecucao()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.statusExecucaoLabel()).isEqualTo("Dados insuficientes");
        assertThat(response.erroExecucao())
                .contains("Dados insuficientes para calcular o PDOC");
        assertThat(response.p10()).isNull();
        assertThat(response.p50()).isNull();
        assertThat(response.p80()).isNull();
        assertThat(response.p95()).isNull();
        assertThat(response.probabilidadeQualquerExcedente()).isNull();
        assertThat(response.probabilidadeExceder5Pct()).isNull();
        assertThat(response.probabilidadeExceder10Pct()).isNull();
        assertThat(response.eacs()).allSatisfy((name, value) -> assertThat(value).isNull());
        assertThat(response.inputs().get("approvedBudget").isNull()).isTrue();
        assertThat(response.inputs().get("actualCost").isNull()).isTrue();
        assertThat(response.inputs().get("committedCost").isNull()).isTrue();
        assertThat(response.warnings().toString()).contains("Orçamento total aprovado ausente");
        assertThat(response.origemDados().get("approvedBudget").get("availability").asText())
                .isEqualTo("ABSENT");
    }

    @Test
    void shouldReturnExistingSnapshotForSameInput() {
        PdocResultadoResponse first =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);
        PdocResultadoResponse second =
                service.calcular("CW38386", null, PdocTriggerType.API, "evento-1");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.snapshotExistente()).isTrue();
        assertThat(snapshotRepository.size()).isEqualTo(1);
    }

    @Test
    void shouldCreateNewSnapshotWhenInputChangesAndKeepHistoryImmutable() {
        PdocResultadoResponse first =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

        inputLoader.bundle = validBundle(obra, "390000.00");
        PdocResultadoResponse second =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

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

        PdocResultadoResponse response =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

        assertThat(response.statusExecucao()).isEqualTo("SUCCESS");
        assertThat(response.snapshotExistente()).isTrue();
        assertThat(snapshotRepository.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnNotFoundForMissingWorksite() {
        when(obraRepository.findAtivasByIdentificador("NAO-EXISTE"))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                service.calcular("NAO-EXISTE", null, PdocTriggerType.MANUAL, null)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("Obra não encontrada");
    }

    @Test
    void shouldAcceptUuidAndCodeIdentifiers() {
        PdocResultadoResponse byCode =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);
        PdocResultadoResponse byUuid =
                service.calcular(obra.getId(), null, PdocTriggerType.MANUAL, null);

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
                service.calcular("AMBIGUA", null, PdocTriggerType.MANUAL, null)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT")
                .hasMessageContaining("Identificador de obra ambíguo");
    }

    @Test
    void shouldReturnCurrentAndPagedHistoryOrderedByExecutionDate() {
        PdocResultadoResponse first =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

        inputLoader.bundle = validBundle(obra, "390000.00");
        PdocResultadoResponse second =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

        inputLoader.bundle = validBundle(obra, "410000.00");
        PdocResultadoResponse third =
                service.calcular("CW38386", null, PdocTriggerType.MANUAL, null);

        assertThat(service.buscarAtual("CW38386").id()).isEqualTo(third.id());

        PdocHistoricoResponse page = service.buscarHistorico("CW38386", 0, 2);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).extracting(PdocResultadoResponse::id)
                .containsExactly(third.id(), second.id());
        assertThat(page.items()).extracting(PdocResultadoResponse::id)
                .doesNotContain(first.id());
    }

    @Test
    void shouldUseCanonicalIdempotencyPayload() {
        PdocInputBundle first = bundleWithInputOrder(
                obra,
                List.of("actualCost", "approvedBudget"),
                "350000.00",
                "1000000.0"
        );
        PdocInputBundle second = bundleWithInputOrder(
                obra,
                List.of("approvedBudget", "actualCost"),
                "350000.0",
                "1000000.00"
        );
        PdocInputBundle changed = bundleWithInputOrder(
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
        assertThat(payload.get("modelVersion")).isEqualTo(PdocEngine.MODEL_VERSION);
        assertThat(payload.get("assumptionsVersion"))
                .isEqualTo(PdocEngine.ASSUMPTIONS_VERSION);
    }

    @Test
    void controllerMethodsShouldNotExposePersistenceEntity() throws Exception {
        assertThat(PdocController.class
                .getMethod(
                        "calcular",
                        String.class,
                        LocalDate.class,
                        String.class,
                        String.class
                )
                .getReturnType())
                .isEqualTo(PdocResultadoResponse.class);
        assertThat(PdocController.class
                .getMethod("atual", String.class)
                .getReturnType())
                .isEqualTo(PdocResultadoResponse.class);
        assertThat(PdocController.class
                .getMethod("historico", String.class, int.class, int.class)
                .getReturnType())
                .isEqualTo(PdocHistoricoResponse.class);
    }

    private static PdocInputBundle validBundle(Obra obra, String actualCost) {
        LocalDate referenceDate = LocalDate.of(2026, 6, 8);
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, PdocInputOrigin> origins = new LinkedHashMap<>();
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

        return new PdocInputBundle(
                obra.getId(),
                obra.getCodigoContrato(),
                referenceDate,
                inputs,
                origins,
                List.of("Há 25 linhas de programação sem quantidade completa."),
                missing,
                new PdocInputBundle.SourceValues(
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

    private static PdocInputBundle insufficientBundle(Obra obra) {
        PdocInputBundle valid = validBundle(obra, "350000.00");
        Map<String, Object> inputs = new LinkedHashMap<>(valid.inputs());
        Map<String, PdocInputOrigin> origins = new LinkedHashMap<>(valid.origins());
        List<String> missing = new ArrayList<>();

        putAbsent(inputs, origins, missing, "approvedBudget", true);
        putAbsent(inputs, origins, missing, "actualCost", true);
        putAbsent(inputs, origins, missing, "committedCost", true);

        return new PdocInputBundle(
                obra.getId(),
                obra.getCodigoContrato(),
                valid.referenceDate(),
                inputs,
                origins,
                List.of(
                        "Orçamento total aprovado ausente; o PDOC não será calculado sem esse dado financeiro.",
                        "Custo realizado ausente; o PDOC não será calculado sem esse dado financeiro.",
                        "Custo comprometido ausente; o PDOC não usará estimativas financeiras substitutas."
                ),
                missing,
                new PdocInputBundle.SourceValues(
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

    private static PdocInputBundle bundleWithInputOrder(
            Obra obra,
            List<String> fieldOrder,
            String actualCost,
            String approvedBudget
    ) {
        PdocInputBundle valid = validBundle(obra, actualCost);
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
        return new PdocInputBundle(
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
            Map<String, PdocInputOrigin> origins,
            List<String> missing,
            String field,
            Object value,
            boolean required
    ) {
        inputs.put(field, value);
        origins.put(
                field,
                new PdocInputOrigin(
                        field,
                        field,
                        value == null ? PdocDataAvailability.ABSENT : PdocDataAvailability.DIRECT,
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
            Map<String, PdocInputOrigin> origins,
            List<String> missing,
            String field,
            boolean required
    ) {
        inputs.put(field, null);
        origins.put(
                field,
                new PdocInputOrigin(
                        field,
                        field,
                        PdocDataAvailability.ABSENT,
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

    private static final class MutableInputLoader implements PdocInputLoader {
        private PdocInputBundle bundle;

        private MutableInputLoader(PdocInputBundle bundle) {
            this.bundle = bundle;
        }

        @Override
        public PdocInputBundle load(Obra obra, LocalDate requestedReferenceDate) {
            return bundle;
        }
    }

    private static final class InMemorySnapshotRepository
            extends PdocSnapshotRepository {

        private final List<PdocSnapshot> snapshots = new ArrayList<>();
        private final Map<String, PdocSnapshot> byIdempotencyKey = new LinkedHashMap<>();
        private final LocalDateTime baseTime = LocalDateTime.of(2026, 6, 22, 10, 0);
        private boolean duplicateNextInsert;

        private InMemorySnapshotRepository(ObjectMapper objectMapper) {
            super(null, objectMapper);
        }

        @Override
        public void insert(PdocSnapshot snapshot) throws DuplicateKeyException {
            PdocSnapshot stored = withSequentialTime(snapshot);
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
        public Optional<PdocSnapshot> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
        }

        @Override
        public Optional<PdocSnapshot> findLatestByObraId(String obraId) {
            return sortedByLatest(obraId).stream().findFirst();
        }

        @Override
        public List<PdocSnapshot> findHistoryByObraId(String obraId, int page, int size) {
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

        private Optional<PdocSnapshot> findById(String id) {
            return snapshots.stream()
                    .filter(snapshot -> snapshot.id().equals(id))
                    .findFirst();
        }

        private void store(PdocSnapshot snapshot) {
            snapshots.add(snapshot);
            byIdempotencyKey.put(snapshot.idempotencyKey(), snapshot);
        }

        private List<PdocSnapshot> sortedByLatest(String obraId) {
            return snapshots.stream()
                    .filter(snapshot -> snapshot.obraId().equals(obraId))
                    .sorted(Comparator
                            .comparing(PdocSnapshot::executedAt)
                            .thenComparing(PdocSnapshot::createdAt)
                            .thenComparing(PdocSnapshot::id)
                            .reversed())
                    .toList();
        }

        private PdocSnapshot withSequentialTime(PdocSnapshot snapshot) {
            LocalDateTime time = baseTime.plusSeconds(snapshots.size());
            return new PdocSnapshot(
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
                    snapshot.eacCpi(),
                    snapshot.eacCpiSpi(),
                    snapshot.eacBottomUp(),
                    snapshot.eacWeighted(),
                    snapshot.cpi(),
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

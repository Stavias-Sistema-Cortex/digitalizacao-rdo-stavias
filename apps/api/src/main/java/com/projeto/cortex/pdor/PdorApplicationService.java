package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.projeto.cortex.intelligence.PdorContextBuilder;
import com.projeto.cortex.intelligence.PdorEngine;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class PdorApplicationService {

    private static final String FONTE = "PDOR";
    static final String REVENUE_ALGORITHM_VERSION = "PDOR-REVENUE-1";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(PdorApplicationService.class);

    private final ObraRepository obraRepository;
    private final PdorInputLoader inputLoader;
    private final PdorSnapshotRepository snapshotRepository;
    private final PdorSnapshotPublicationService publicationService;
    private final ObjectMapper objectMapper;
    private final PdorContextBuilder contextBuilder;
    private final PdorEngine engine;
    private final CortexOperationalMemoryService memoryService;
    private final PdorExecutionInitiatorResolver initiatorResolver;
    private final PdorExplainabilityBuilder explainabilityBuilder;

    @Autowired
    public PdorApplicationService(
            ObraRepository obraRepository,
            PdorInputLoader inputLoader,
            PdorSnapshotRepository snapshotRepository,
            PdorSnapshotPublicationService publicationService,
            ObjectMapper objectMapper,
            CortexOperationalMemoryService memoryService,
            PdorExecutionInitiatorResolver initiatorResolver
    ) {
        this(
                obraRepository,
                inputLoader,
                snapshotRepository,
                publicationService,
                objectMapper,
                memoryService,
                new PdorContextBuilder(),
                new PdorEngine(),
                initiatorResolver
        );
    }

    /**
     * Mantém a construção explícita usada por integrações fora do contexto
     * Spring; em runtime o construtor autowired usa o publicador transacional
     * proxied pelo container.
     */
    public PdorApplicationService(
            ObraRepository obraRepository,
            PdorInputLoader inputLoader,
            PdorSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            CortexOperationalMemoryService memoryService,
            PdorExecutionInitiatorResolver initiatorResolver
    ) {
        this(
                obraRepository,
                inputLoader,
                snapshotRepository,
                new PdorSnapshotPublicationService(snapshotRepository),
                objectMapper,
                memoryService,
                new PdorContextBuilder(),
                new PdorEngine(),
                initiatorResolver
        );
    }

    PdorApplicationService(
            ObraRepository obraRepository,
            PdorInputLoader inputLoader,
            PdorSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            CortexOperationalMemoryService memoryService
    ) {
        this(
                obraRepository, inputLoader, snapshotRepository, objectMapper,
                memoryService, new PdorContextBuilder(), new PdorEngine(), null
        );
    }

    PdorApplicationService(
            ObraRepository obraRepository,
            PdorInputLoader inputLoader,
            PdorSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            CortexOperationalMemoryService memoryService,
            PdorContextBuilder contextBuilder,
            PdorEngine engine
    ) {
        this(
                obraRepository, inputLoader, snapshotRepository, objectMapper,
                memoryService, contextBuilder, engine, null
        );
    }

    PdorApplicationService(
            ObraRepository obraRepository,
            PdorInputLoader inputLoader,
            PdorSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            CortexOperationalMemoryService memoryService,
            PdorContextBuilder contextBuilder,
            PdorEngine engine,
            PdorExecutionInitiatorResolver initiatorResolver
    ) {
        this(
                obraRepository,
                inputLoader,
                snapshotRepository,
                new PdorSnapshotPublicationService(snapshotRepository),
                objectMapper,
                memoryService,
                contextBuilder,
                engine,
                initiatorResolver
        );
    }

    private PdorApplicationService(
            ObraRepository obraRepository,
            PdorInputLoader inputLoader,
            PdorSnapshotRepository snapshotRepository,
            PdorSnapshotPublicationService publicationService,
            ObjectMapper objectMapper,
            CortexOperationalMemoryService memoryService,
            PdorContextBuilder contextBuilder,
            PdorEngine engine,
            PdorExecutionInitiatorResolver initiatorResolver
    ) {
        this.obraRepository = obraRepository;
        this.inputLoader = inputLoader;
        this.snapshotRepository = snapshotRepository;
        this.publicationService = publicationService;
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.memoryService = memoryService;
        this.contextBuilder = contextBuilder;
        this.engine = engine;
        this.initiatorResolver = initiatorResolver;
        this.explainabilityBuilder = new PdorExplainabilityBuilder(this.objectMapper);
    }

    public PdorResultadoResponse calcular(
            String obraIdentifier,
            LocalDate referenceDate,
            PdorTriggerType triggerType,
            String originEventId
    ) {
        Obra obra = localizarObra(obraIdentifier);
        PdorInputBundle inputs = inputLoader.load(obra, referenceDate);
        String idempotencyKey = calculateIdempotencyKey(inputs);

        return snapshotRepository.findByIdempotencyKey(idempotencyKey)
                .map(snapshot -> {
                    publicationService.repairOntology(
                            () -> registrarNoGrafo(snapshot, obra)
                    );
                    return toResponse(snapshot, obra, true);
                })
                .orElseGet(() -> calcularNovoSnapshot(
                        obra,
                        inputs,
                        triggerType,
                        originEventId,
                        idempotencyKey
                ));
    }

    public PdorResultadoResponse buscarAtual(String obraIdentifier) {
        PdorResultadoResponse current = buscarAtualSeExistente(obraIdentifier);
        if (current == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Nenhum snapshot PDOR encontrado para a obra."
            );
        }
        return current;
    }

    public PdorResultadoResponse buscarAtualSeExistente(String obraIdentifier) {
        Obra obra = localizarObra(obraIdentifier);
        return snapshotRepository.findCurrentByObraId(obra.getId())
                .or(() -> snapshotRepository.findLatestByObraId(obra.getId()))
                .map(snapshot -> toResponse(snapshot, obra, true))
                .orElse(null);
    }

    public PdorHistoricoResponse buscarHistorico(
            String obraIdentifier,
            int page,
            int size
    ) {
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page não pode ser negativo."
            );
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "size deve estar entre 1 e 100."
            );
        }
        long offset = Math.multiplyExact((long) page, size);
        if (offset > 1_000_000L) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page excede o limite permitido."
            );
        }

        Obra obra = localizarObra(obraIdentifier);
        long totalElements = snapshotRepository.countByObraId(obra.getId());
        List<PdorResultadoResponse> items =
                snapshotRepository.findHistoryByObraId(obra.getId(), page, size)
                        .stream()
                        .map(snapshot -> toResponse(snapshot, obra, true))
                        .toList();

        int totalPages = totalElements == 0
                ? 0
                : (int) Math.ceil((double) totalElements / size);

        return new PdorHistoricoResponse(
                items,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 < totalPages
        );
    }

    private PdorResultadoResponse calcularNovoSnapshot(
            Obra obra,
            PdorInputBundle inputs,
            PdorTriggerType triggerType,
            String originEventId,
            String idempotencyKey
    ) {
        PdorSnapshot previous = snapshotRepository.findCurrentByObraId(obra.getId())
                .or(() -> snapshotRepository.findLatestByObraId(obra.getId()))
                .orElse(null);
        PdorExecutionInitiator initiator = initiatorResolver == null
                ? PdorExecutionInitiator.process()
                : initiatorResolver.resolve();
        PdorSnapshot baseSnapshot;

        try {
            if (!inputs.canCalculate()) {
                baseSnapshot = buildInsufficientDataSnapshot(
                        inputs,
                        triggerType,
                        originEventId,
                        idempotencyKey
                );
            } else {
                baseSnapshot = buildCalculationSnapshot(
                        inputs,
                        triggerType,
                        originEventId,
                        idempotencyKey
                );
            }
        } catch (RuntimeException exception) {
            throw recordCalculationFailure(
                    obra, inputs, previous, triggerType, initiator, exception
            );
        }

        PdorSnapshot snapshot = baseSnapshot.withExplainability(
                calculateDataVersion(inputs),
                explainabilityBuilder.build(baseSnapshot, inputs, previous),
                initiator
        );

        try {
            publicationService.publish(
                    snapshot,
                    () -> registrarNoGrafo(snapshot, obra)
            );
            return toResponse(snapshot, obra, false);
        } catch (DuplicateKeyException exception) {
            return snapshotRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> toResponse(existing, obra, true))
                    .orElseThrow(() -> exception);
        }
    }

    /**
     * Liga o snapshot ao grafo ontológico: obra e PDOR como objetos, relação
     * ANALISA entre eles, evento operacional visível na timeline da obra e
     * evidências numéricas de receita consultáveis pela ontologia.
     */
    private void registrarNoGrafo(PdorSnapshot snapshot, Obra obra) {
        memoryService.registrarObjeto(
                "OBRA",
                obra.getId(),
                snapshot.codigoObra(),
                obra.getNome(),
                obra.getStatus(),
                FONTE
        );
        memoryService.registrarObjeto(
                "PDOR",
                snapshot.id(),
                snapshot.codigoObra(),
                "Previsão de receita " + snapshot.codigoObra(),
                snapshot.executionStatus().name(),
                FONTE
        );
        memoryService.registrarRelacaoAtiva(
                "PDOR",
                snapshot.id(),
                "OBRA",
                obra.getId(),
                "ANALISA",
                FONTE,
                "Snapshot PDOR analisa a receita da obra."
        );

        String tipoEvento = switch (snapshot.executionStatus()) {
            case SUCCESS -> "PDOR_CALCULADO";
            case INSUFFICIENT_DATA -> "PDOR_INSUFICIENTE";
            case FAILED -> "PDOR_FALHOU";
        };

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 2);
        payload.put("obraId", obra.getId());
        payload.put("snapshotId", snapshot.id());
        payload.put("dataReferencia", snapshot.referenceDate());
        payload.put("statusExecucao", snapshot.executionStatus().name());
        payload.put("receitaEstimadaFinal", snapshot.racWeighted());
        payload.put("p50Receita", snapshot.revenueP50());
        payload.put("p80Receita", snapshot.revenueP80());
        payload.put(
                "probabilidadeAbaixoContrato",
                snapshot.probabilityBelowContract()
        );

        Map<String, Object> obraRelacionada = new LinkedHashMap<>();
        obraRelacionada.put("tipo", "OBRA");
        obraRelacionada.put("id", obra.getId());

        List<Map<String, Object>> relatedEntities = new ArrayList<>();
        relatedEntities.add(obraRelacionada);
        if (snapshot.evidence() != null && snapshot.evidence().isArray()) {
            snapshot.evidence().forEach(evidence -> {
                String entityType = evidence.path("tipoEntidade").asText("");
                String entityId = evidence.path("entidadeId").asText("");
                if (!entityType.isBlank() && !entityId.isBlank()) {
                    memoryService.registrarRelacaoAtiva(
                            "PDOR", snapshot.id(), entityType, entityId,
                            "GERADO_A_PARTIR_DE", FONTE,
                            "Evidência utilizada na execução PDOR."
                    );
                    relatedEntities.add(Map.of("tipo", entityType, "id", entityId));
                }
            });
        }

        payload.put("versaoModelo", snapshot.modelVersion());
        payload.put("versaoPremissas", snapshot.assumptionsVersion());
        payload.put("versaoDados", snapshot.dataVersion());
        payload.put("calibracao", snapshot.calibrationStatus());
        payload.put("confianca", snapshot.confidence());
        payload.put("iniciadoPor", snapshot.initiatedBy());
        payload.put("tipoIniciador", snapshot.initiatorType());
        payload.put("comparacaoAnterior", snapshot.previousComparison());

        memoryService.registrarEventoDetalhado(
                snapshot.id(),
                "PDOR",
                snapshot.id(),
                tipoEvento,
                FONTE,
                obra.getId(),
                null,
                null,
                relatedEntities,
                "ONLINE",
                "SYNCED",
                snapshot.executedAt(),
                LocalDateTime.now(),
                2,
                payload
        );

        if (snapshot.executionStatus() == PdorExecutionStatus.SUCCESS) {
            Map<String, Object> evidencias = new LinkedHashMap<>();
            evidencias.put("receitaPrevistaFinal", snapshot.racWeighted());
            evidencias.put("receitaP50", snapshot.revenueP50());
            evidencias.put("receitaP80", snapshot.revenueP80());
            evidencias.put(
                    "probabilidadeAbaixoContrato",
                    snapshot.probabilityBelowContract()
            );
            evidencias.put("nivelRiscoReceita", snapshot.riskLevel());
            evidencias.put("confiancaPdor", snapshot.confidence());
            evidencias.put("dataReferenciaPdor", snapshot.referenceDate());

            memoryService.registrarEvidencias(
                    "PDOR",
                    snapshot.id(),
                    FONTE,
                    evidencias
            );
            memoryService.registrarEvidencias(
                    "OBRA",
                    obra.getId(),
                    FONTE,
                    evidencias
            );
        }
    }

    private PdorSnapshot buildCalculationSnapshot(
            PdorInputBundle inputs,
            PdorTriggerType triggerType,
            String originEventId,
            String idempotencyKey
    ) {
        PdorEngine.PdorContext context =
                contextBuilder.build(inputs.toSourceSnapshot());
        PdorEngine.PdorResult result =
                engine.calculate(context, inputs.historicalSeries());

        PdorSnapshot snapshot = successSnapshot(
                inputs,
                triggerType,
                originEventId,
                idempotencyKey,
                result
        );
        return withRevenueMetadata(
                snapshot,
                inputs,
                objectMapper.valueToTree(result.assumptions()),
                true
        );
    }

    private PdorSnapshot buildInsufficientDataSnapshot(
            PdorInputBundle inputs,
            PdorTriggerType triggerType,
            String originEventId,
            String idempotencyKey
    ) {
        String error =
                "Dados insuficientes para calcular o PDOR. Campos ausentes: "
                        + String.join(", ", inputs.missingRequiredFields())
                        + ".";

        List<String> warnings = new ArrayList<>(inputs.warnings());
        warnings.add(error);

        PdorSnapshot snapshot = baseSnapshot(
                inputs,
                triggerType,
                originEventId,
                idempotencyKey,
                PdorExecutionStatus.INSUFFICIENT_DATA,
                warnings,
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
                error
        );
        return withRevenueMetadata(
                snapshot,
                inputs,
                objectMapper.valueToTree(Map.of(
                        "version", PdorEngine.ASSUMPTIONS_VERSION
                )),
                false
        );
    }

    private PdorSnapshot successSnapshot(
            PdorInputBundle inputs,
            PdorTriggerType triggerType,
            String originEventId,
            String idempotencyKey,
            PdorEngine.PdorResult result
    ) {
        PdorEngine.RevenueMetrics evm = result.evm();

        return baseSnapshot(
                inputs,
                triggerType,
                originEventId,
                idempotencyKey,
                PdorExecutionStatus.SUCCESS,
                inputs.warnings(),
                result.calculationMode().name(),
                result.calibrationStatus().name(),
                result.projectPhase().name(),
                result.riskLevel().name(),
                result.revenueP10(),
                result.revenueP50(),
                result.revenueP80(),
                result.revenueP95(),
                evm.racRci(),
                evm.racRciSpi(),
                evm.racBottomUp(),
                evm.weightedRac(),
                decimal(evm.rci()),
                decimal(evm.spi()),
                probability(result.simulationProbabilityBelowContract()),
                probability(result.simulationProbabilityBelow95Pct()),
                probability(result.simulationProbabilityBelow90Pct()),
                probability(result.heuristicRiskScore()),
                probability(result.confidence()),
                result.simulationConverged(),
                result.simulationIterationsUsed(),
                objectMapper.valueToTree(driverMaps(result.drivers())),
                null
        );
    }

    private PdorSnapshot baseSnapshot(
            PdorInputBundle inputs,
            PdorTriggerType triggerType,
            String originEventId,
            String idempotencyKey,
            PdorExecutionStatus status,
            List<String> warnings,
            String calculationMode,
            String calibrationStatus,
            String projectPhase,
            String riskLevel,
            BigDecimal revenueP10,
            BigDecimal revenueP50,
            BigDecimal revenueP80,
            BigDecimal revenueP95,
            BigDecimal racRci,
            BigDecimal racRciSpi,
            BigDecimal racBottomUp,
            BigDecimal racWeighted,
            BigDecimal rci,
            BigDecimal spi,
            BigDecimal probabilityBelowContract,
            BigDecimal probabilityBelow95Pct,
            BigDecimal probabilityBelow90Pct,
            BigDecimal heuristicRiskScore,
            BigDecimal confidence,
            Boolean simulationConverged,
            Integer simulationIterations,
            JsonNode drivers,
            String executionError
    ) {
        return new PdorSnapshot(
                UUID.randomUUID().toString(),
                inputs.obraId(),
                inputs.codigoObra(),
                LocalDateTime.now(Clock.systemUTC()),
                inputs.referenceDate(),
                PdorEngine.MODEL_VERSION,
                PdorEngine.ASSUMPTIONS_VERSION,
                status,
                triggerType == null ? PdorTriggerType.MANUAL : triggerType,
                normalizeOriginEventId(originEventId),
                idempotencyKey,
                objectMapper.valueToTree(sortedMap(inputs.inputs())),
                objectMapper.valueToTree(sortedMap(inputs.originsAsMap())),
                objectMapper.valueToTree(warnings),
                calculationMode,
                calibrationStatus,
                projectPhase,
                riskLevel,
                revenueP10,
                revenueP50,
                revenueP80,
                revenueP95,
                racRci,
                racRciSpi,
                racBottomUp,
                racWeighted,
                rci,
                spi,
                probabilityBelowContract,
                probabilityBelow95Pct,
                probabilityBelow90Pct,
                heuristicRiskScore,
                confidence,
                simulationConverged,
                simulationIterations,
                drivers == null ? objectMapper.createArrayNode() : drivers,
                executionError,
                LocalDateTime.now(Clock.systemUTC())
        );
    }

    private PdorSnapshot withRevenueMetadata(
            PdorSnapshot snapshot,
            PdorInputBundle inputs,
            JsonNode assumptions,
            boolean current
    ) {
        Instant executedAtUtc = snapshot.executedAt()
                .toInstant(ZoneOffset.UTC);
        return snapshot.withRevenueMetadata(
                REVENUE_ALGORITHM_VERSION,
                inputs.revenueEvidenceIds(),
                inputs.evidenceHighWaterMark(),
                inputs.revenueCoverageCode(),
                assumptions,
                executedAtUtc,
                !current,
                current
        );
    }

    private PdorCalculationException recordCalculationFailure(
            Obra obra,
            PdorInputBundle inputs,
            PdorSnapshot previous,
            PdorTriggerType triggerType,
            PdorExecutionInitiator initiator,
            RuntimeException exception
    ) {
        String correlationId = UUID.randomUUID().toString();
        PdorTriggerType normalizedTrigger = triggerType == null
                ? PdorTriggerType.MANUAL
                : triggerType;
        try {
            snapshotRepository.recordFailureAndMarkCurrentStale(
                    correlationId,
                    obra.getId(),
                    previous == null ? null : previous.id(),
                    inputs.evidenceHighWaterMark(),
                    normalizedTrigger,
                    initiator.id(),
                    Instant.now()
            );
        } catch (RuntimeException auditFailure) {
            exception.addSuppressed(auditFailure);
        }
        LOGGER.error(
                "PDOR calculation failed; correlationId={}, obraId={}, failureCategory=CALCULATION_RUNTIME",
                correlationId,
                obra.getId()
        );
        return new PdorCalculationException(correlationId, exception);
    }

    private Obra localizarObra(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador da obra é obrigatório."
            );
        }

        String normalizedIdentifier = identifier.trim();
        List<Obra> obras =
                obraRepository.findAtivasByIdentificador(normalizedIdentifier);

        if (obras.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada: " + identifier
            );
        }

        if (obras.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Identificador de obra ambíguo: " + identifier
            );
        }

        return obras.getFirst();
    }

    private PdorResultadoResponse toResponse(
            PdorSnapshot snapshot,
            Obra obra,
            boolean snapshotExistente
    ) {
        return PdorResultadoResponse.from(
                snapshot,
                new PdorResultadoResponse.ObraResumo(
                        obra.getId(),
                        obra.getCodigoContrato(),
                        obra.getCodigoCw(),
                        obra.getCodigoInterno(),
                        obra.getNome()
                ),
                snapshotExistente
        );
    }

    String calculateIdempotencyKey(PdorInputBundle inputs) {
        try {
            String json = objectMapper.writeValueAsString(
                    canonicalize(idempotencyPayload(inputs))
            );
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível calcular a chave de idempotência do PDOR.",
                    exception
            );
        }
    }

    String calculateDataVersion(PdorInputBundle inputs) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("obraId", inputs.obraId());
            payload.put("referenceDate", inputs.referenceDate());
            payload.put("inputs", inputs.inputs());
            payload.put("inputOrigins", inputs.originsAsMap());
            payload.put("missingRequiredFields", inputs.missingRequiredFields());
            payload.put("evidenceReferences", inputs.evidenceReferences());
            String json = objectMapper.writeValueAsString(canonicalize(payload));
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível calcular a versão dos dados do PDOR.",
                    exception
            );
        }
    }

    Map<String, Object> idempotencyPayload(PdorInputBundle inputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("obraId", inputs.obraId());
        payload.put("referenceDate", inputs.referenceDate());
        payload.put("modelVersion", PdorEngine.MODEL_VERSION);
        payload.put("algorithmVersion", REVENUE_ALGORITHM_VERSION);
        payload.put("assumptionsVersion", PdorEngine.ASSUMPTIONS_VERSION);
        payload.put("inputs", inputs.inputs());
        payload.put("inputAvailability", inputAvailability(inputs));
        payload.put("missingRequiredFields", inputs.missingRequiredFields());
        return payload;
    }

    private Map<String, Object> inputAvailability(PdorInputBundle inputs) {
        Map<String, Object> result = new TreeMap<>();
        inputs.origins().forEach((field, origin) -> {
            Map<String, Object> availability = new TreeMap<>();
            availability.put("availability", origin.availability().name());
            availability.put("required", origin.required());
            result.put(field, availability);
        });
        return result;
    }

    private Map<String, Object> sortedMap(Map<String, Object> map) {
        return new TreeMap<>(map);
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> canonical = new TreeMap<>();
            map.forEach((key, item) ->
                    canonical.put(String.valueOf(key), canonicalize(item)));
            return canonical;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::canonicalize)
                    .sorted(Comparator.comparing(this::canonicalSortKey))
                    .toList();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    private String canonicalSortKey(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private List<Map<String, Object>> driverMaps(
            List<PdorEngine.PdorDriver> drivers
    ) {
        return drivers.stream()
                .map(driver -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("code", driver.code());
                    map.put("description", driver.description());
                    map.put("impact", driver.impact());
                    map.put("evidence", driver.evidence());
                    return map;
                })
                .toList();
    }

    private BigDecimal probability(double value) {
        return BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private String normalizeOriginEventId(String originEventId) {
        if (originEventId == null || originEventId.isBlank()) {
            return null;
        }
        return originEventId.trim();
    }
}

package com.projeto.cortex.pdoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.projeto.cortex.intelligence.PdocContextBuilder;
import com.projeto.cortex.intelligence.PdocEngine;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class PdocApplicationService {

    private final ObraRepository obraRepository;
    private final PdocInputLoader inputLoader;
    private final PdocSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final PdocContextBuilder contextBuilder;
    private final PdocEngine engine;

    @Autowired
    public PdocApplicationService(
            ObraRepository obraRepository,
            PdocInputLoader inputLoader,
            PdocSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper
    ) {
        this(
                obraRepository,
                inputLoader,
                snapshotRepository,
                objectMapper,
                new PdocContextBuilder(),
                new PdocEngine()
        );
    }

    PdocApplicationService(
            ObraRepository obraRepository,
            PdocInputLoader inputLoader,
            PdocSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            PdocContextBuilder contextBuilder,
            PdocEngine engine
    ) {
        this.obraRepository = obraRepository;
        this.inputLoader = inputLoader;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.contextBuilder = contextBuilder;
        this.engine = engine;
    }

    public PdocResultadoResponse calcular(
            String obraIdentifier,
            LocalDate referenceDate,
            PdocTriggerType triggerType,
            String originEventId
    ) {
        Obra obra = localizarObra(obraIdentifier);
        PdocInputBundle inputs = inputLoader.load(obra, referenceDate);
        String idempotencyKey = calculateIdempotencyKey(inputs);

        return snapshotRepository.findByIdempotencyKey(idempotencyKey)
                .map(snapshot -> toResponse(snapshot, obra, true))
                .orElseGet(() -> calcularNovoSnapshot(
                        obra,
                        inputs,
                        triggerType,
                        originEventId,
                        idempotencyKey
                ));
    }

    public PdocResultadoResponse buscarAtual(String obraIdentifier) {
        Obra obra = localizarObra(obraIdentifier);
        PdocSnapshot snapshot = snapshotRepository.findLatestByObraId(obra.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Nenhum snapshot PDOC encontrado para a obra."
                ));

        return toResponse(snapshot, obra, true);
    }

    public PdocHistoricoResponse buscarHistorico(
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

        Obra obra = localizarObra(obraIdentifier);
        long totalElements = snapshotRepository.countByObraId(obra.getId());
        List<PdocResultadoResponse> items =
                snapshotRepository.findHistoryByObraId(obra.getId(), page, size)
                        .stream()
                        .map(snapshot -> toResponse(snapshot, obra, true))
                        .toList();

        int totalPages = totalElements == 0
                ? 0
                : (int) Math.ceil((double) totalElements / size);

        return new PdocHistoricoResponse(
                items,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 < totalPages
        );
    }

    private PdocResultadoResponse calcularNovoSnapshot(
            Obra obra,
            PdocInputBundle inputs,
            PdocTriggerType triggerType,
            String originEventId,
            String idempotencyKey
    ) {
        PdocSnapshot snapshot;

        if (!inputs.canCalculate()) {
            snapshot = buildInsufficientDataSnapshot(
                    inputs,
                    triggerType,
                    originEventId,
                    idempotencyKey
            );
        } else {
            snapshot = buildCalculationSnapshot(
                    inputs,
                    triggerType,
                    originEventId,
                    idempotencyKey
            );
        }

        try {
            snapshotRepository.insert(snapshot);
            return toResponse(snapshot, obra, false);
        } catch (DuplicateKeyException exception) {
            return snapshotRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> toResponse(existing, obra, true))
                    .orElseThrow(() -> exception);
        }
    }

    private PdocSnapshot buildCalculationSnapshot(
            PdocInputBundle inputs,
            PdocTriggerType triggerType,
            String originEventId,
            String idempotencyKey
    ) {
        try {
            PdocEngine.PdocContext context =
                    contextBuilder.build(inputs.toSourceSnapshot());
            PdocEngine.PdocResult result = engine.calculate(context);

            return successSnapshot(
                    inputs,
                    triggerType,
                    originEventId,
                    idempotencyKey,
                    result
            );
        } catch (RuntimeException exception) {
            return failedSnapshot(
                    inputs,
                    triggerType,
                    originEventId,
                    idempotencyKey,
                    "Falha ao executar o motor PDOC: " + exception.getMessage()
            );
        }
    }

    private PdocSnapshot buildInsufficientDataSnapshot(
            PdocInputBundle inputs,
            PdocTriggerType triggerType,
            String originEventId,
            String idempotencyKey
    ) {
        String error =
                "Dados insuficientes para calcular o PDOC. Campos ausentes: "
                        + String.join(", ", inputs.missingRequiredFields())
                        + ".";

        List<String> warnings = new ArrayList<>(inputs.warnings());
        warnings.add(error);

        return baseSnapshot(
                inputs,
                triggerType,
                originEventId,
                idempotencyKey,
                PdocExecutionStatus.INSUFFICIENT_DATA,
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
    }

    private PdocSnapshot failedSnapshot(
            PdocInputBundle inputs,
            PdocTriggerType triggerType,
            String originEventId,
            String idempotencyKey,
            String error
    ) {
        List<String> warnings = new ArrayList<>(inputs.warnings());
        warnings.add(error);

        return baseSnapshot(
                inputs,
                triggerType,
                originEventId,
                idempotencyKey,
                PdocExecutionStatus.FAILED,
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
    }

    private PdocSnapshot successSnapshot(
            PdocInputBundle inputs,
            PdocTriggerType triggerType,
            String originEventId,
            String idempotencyKey,
            PdocEngine.PdocResult result
    ) {
        PdocEngine.EvmMetrics evm = result.evm();

        return baseSnapshot(
                inputs,
                triggerType,
                originEventId,
                idempotencyKey,
                PdocExecutionStatus.SUCCESS,
                inputs.warnings(),
                result.calculationMode().name(),
                result.calibrationStatus().name(),
                result.projectPhase().name(),
                result.riskLevel().name(),
                result.costP10(),
                result.costP50(),
                result.costP80(),
                result.costP95(),
                evm.estimateAtCompletionCpi(),
                evm.estimateAtCompletionCpiSpi(),
                evm.estimateAtCompletionBottomUp(),
                evm.weightedEstimateAtCompletion(),
                decimal(evm.cpi()),
                decimal(evm.spi()),
                probability(result.simulationProbabilityAnyOverrun()),
                probability(result.simulationProbabilityOverFivePercent()),
                probability(result.simulationProbabilityOverTenPercent()),
                probability(result.heuristicRiskScore()),
                probability(result.confidence()),
                result.simulationConverged(),
                result.simulationIterationsUsed(),
                objectMapper.valueToTree(driverMaps(result.drivers())),
                null
        );
    }

    private PdocSnapshot baseSnapshot(
            PdocInputBundle inputs,
            PdocTriggerType triggerType,
            String originEventId,
            String idempotencyKey,
            PdocExecutionStatus status,
            List<String> warnings,
            String calculationMode,
            String calibrationStatus,
            String projectPhase,
            String riskLevel,
            BigDecimal costP10,
            BigDecimal costP50,
            BigDecimal costP80,
            BigDecimal costP95,
            BigDecimal eacCpi,
            BigDecimal eacCpiSpi,
            BigDecimal eacBottomUp,
            BigDecimal eacWeighted,
            BigDecimal cpi,
            BigDecimal spi,
            BigDecimal probabilityAnyOverrun,
            BigDecimal probabilityOverFivePercent,
            BigDecimal probabilityOverTenPercent,
            BigDecimal heuristicRiskScore,
            BigDecimal confidence,
            Boolean simulationConverged,
            Integer simulationIterations,
            JsonNode drivers,
            String executionError
    ) {
        return new PdocSnapshot(
                UUID.randomUUID().toString(),
                inputs.obraId(),
                inputs.codigoObra(),
                LocalDateTime.now(),
                inputs.referenceDate(),
                PdocEngine.MODEL_VERSION,
                PdocEngine.ASSUMPTIONS_VERSION,
                status,
                triggerType == null ? PdocTriggerType.MANUAL : triggerType,
                normalizeOriginEventId(originEventId),
                idempotencyKey,
                objectMapper.valueToTree(sortedMap(inputs.inputs())),
                objectMapper.valueToTree(sortedMap(inputs.originsAsMap())),
                objectMapper.valueToTree(warnings),
                calculationMode,
                calibrationStatus,
                projectPhase,
                riskLevel,
                costP10,
                costP50,
                costP80,
                costP95,
                eacCpi,
                eacCpiSpi,
                eacBottomUp,
                eacWeighted,
                cpi,
                spi,
                probabilityAnyOverrun,
                probabilityOverFivePercent,
                probabilityOverTenPercent,
                heuristicRiskScore,
                confidence,
                simulationConverged,
                simulationIterations,
                drivers == null ? objectMapper.createArrayNode() : drivers,
                executionError,
                LocalDateTime.now()
        );
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

    private PdocResultadoResponse toResponse(
            PdocSnapshot snapshot,
            Obra obra,
            boolean snapshotExistente
    ) {
        return PdocResultadoResponse.from(
                snapshot,
                new PdocResultadoResponse.ObraResumo(
                        obra.getId(),
                        obra.getCodigoContrato(),
                        obra.getCodigoCw(),
                        obra.getCodigoInterno(),
                        obra.getNome()
                ),
                snapshotExistente
        );
    }

    String calculateIdempotencyKey(PdocInputBundle inputs) {
        try {
            String json = objectMapper.writeValueAsString(
                    canonicalize(idempotencyPayload(inputs))
            );
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível calcular a chave de idempotência do PDOC.",
                    exception
            );
        }
    }

    Map<String, Object> idempotencyPayload(PdocInputBundle inputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("obraId", inputs.obraId());
        payload.put("referenceDate", inputs.referenceDate());
        payload.put("modelVersion", PdocEngine.MODEL_VERSION);
        payload.put("assumptionsVersion", PdocEngine.ASSUMPTIONS_VERSION);
        payload.put("inputs", inputs.inputs());
        payload.put("inputAvailability", inputAvailability(inputs));
        payload.put("missingRequiredFields", inputs.missingRequiredFields());
        return payload;
    }

    private Map<String, Object> inputAvailability(PdocInputBundle inputs) {
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
            List<PdocEngine.PdocDriver> drivers
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

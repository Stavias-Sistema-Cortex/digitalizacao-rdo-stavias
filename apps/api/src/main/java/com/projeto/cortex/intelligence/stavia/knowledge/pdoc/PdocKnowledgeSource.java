package com.projeto.cortex.intelligence.stavia.knowledge.pdoc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import com.projeto.cortex.pdoc.PdocExecutionStatus;
import com.projeto.cortex.pdoc.PdocSnapshot;
import com.projeto.cortex.pdoc.PdocSnapshotRepository;

@Component
public class PdocKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PdocSnapshotRepository snapshotRepository;
    private final ObraRepository obraRepository;

    public PdocKnowledgeSource(
            PdocSnapshotRepository snapshotRepository,
            ObraRepository obraRepository
    ) {
        this.snapshotRepository = snapshotRepository;
        this.obraRepository = obraRepository;
    }

    @Override
    public String sourceName() {
        return "pdoc";
    }

    @Override
    public String sourceVersion() {
        return StaviaVersions.PDOC_SOURCE;
    }

    @Override
    public boolean supports(
            StaviaKnowledgeRequest request
    ) {
        if (request == null) {
            return false;
        }

        if (
                !request.permissions().contains(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        ) {
            return false;
        }

        return request.intent()
                == StaviaIntent.CONSULTAR_PDOC;
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        List<Obra> obras =
                obraRepository.findAtivasByIdentificador(
                        request.worksiteId()
                );

        if (obras.size() != 1) {
            return List.of(
                    unavailableWorksiteEvidence(request.worksiteId())
            );
        }

        Obra obra = obras.getFirst();

        return snapshotRepository.findLatestByObraId(obra.getId())
                .map(snapshot -> toEvidence(snapshot, obra))
                .map(List::of)
                .orElseGet(() ->
                        List.of(noSnapshotEvidence(obra))
                );
    }

    private StaviaEvidence unavailableWorksiteEvidence(
            String worksiteId
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put("obraId", worksiteId);
        attributes.put("statusExecucao", "OBRA_NAO_LOCALIZADA");
        attributes.put("sourceMode", "OPERATIONAL");

        return new StaviaEvidence(
                StaviaEvidenceTypes.PDOC,
                "PDOC:OBRA_NAO_LOCALIZADA:" + worksiteId,
                "O PDOC não localizou a obra informada no cadastro.",
                null,
                false,
                attributes
        );
    }

    private StaviaEvidence noSnapshotEvidence(Obra obra) {
        Map<String, Object> attributes =
                baseAttributes(obra);

        attributes.put("statusExecucao", "NO_SNAPSHOT");
        attributes.put("statusExecucaoLabel", "Sem snapshot");
        attributes.put("sourceMode", "OPERATIONAL");

        return new StaviaEvidence(
                StaviaEvidenceTypes.PDOC,
                "PDOC:NO_SNAPSHOT:" + obra.getId(),
                "Nenhum snapshot PDOC encontrado para a obra "
                        + obraCode(obra)
                        + ".",
                null,
                false,
                attributes
        );
    }

    private StaviaEvidence toEvidence(
            PdocSnapshot snapshot,
            Obra obra
    ) {
        Map<String, Object> attributes =
                baseAttributes(obra);

        attributes.put("snapshotId", snapshot.id());
        putDate(attributes, "dataReferencia", snapshot.referenceDate());
        putDateTime(attributes, "dataExecucao", snapshot.executedAt());
        putText(attributes, "versaoModelo", snapshot.modelVersion());
        putText(
                attributes,
                "versaoPremissas",
                snapshot.assumptionsVersion()
        );
        attributes.put(
                "statusExecucao",
                snapshot.executionStatus().name()
        );
        attributes.put(
                "statusExecucaoLabel",
                snapshot.executionStatus().label()
        );
        attributes.put("sourceMode", "OPERATIONAL");
        putText(attributes, "modoCalculo", snapshot.calculationMode());
        putText(
                attributes,
                "calibrationStatus",
                snapshot.calibrationStatus()
        );
        putText(attributes, "faseObra", snapshot.projectPhase());
        putText(attributes, "nivelRisco", snapshot.riskLevel());
        putDecimal(attributes, "costP10", snapshot.costP10());
        putDecimal(attributes, "costP50", snapshot.costP50());
        putDecimal(attributes, "costP80", snapshot.costP80());
        putDecimal(attributes, "costP95", snapshot.costP95());
        putDecimal(
                attributes,
                "probabilityAnyOverrun",
                snapshot.probabilityAnyOverrun()
        );
        putDecimal(
                attributes,
                "probabilityOverFivePercent",
                snapshot.probabilityOverFivePercent()
        );
        putDecimal(
                attributes,
                "probabilityOverTenPercent",
                snapshot.probabilityOverTenPercent()
        );
        putDecimal(
                attributes,
                "heuristicRiskScore",
                snapshot.heuristicRiskScore()
        );
        putDecimal(attributes, "confidence", snapshot.confidence());
        putBoolean(
                attributes,
                "simulationConverged",
                snapshot.simulationConverged()
        );
        putInteger(
                attributes,
                "simulationIterations",
                snapshot.simulationIterations()
        );
        putText(
                attributes,
                "erroExecucao",
                snapshot.executionError()
        );

        List<String> warnings = jsonTextList(snapshot.warnings());
        attributes.put("warnings", warnings);
        attributes.put(
                "missingRequiredFields",
                missingRequiredFields(snapshot.inputOrigins())
        );

        if (snapshot.drivers() != null && !snapshot.drivers().isNull()) {
            attributes.put("drivers", snapshot.drivers());
        }

        if (snapshot.inputs() != null && !snapshot.inputs().isNull()) {
            attributes.put("inputs", snapshot.inputs());
        }

        if (
                snapshot.inputOrigins() != null
                && !snapshot.inputOrigins().isNull()
        ) {
            attributes.put("origemDados", snapshot.inputOrigins());
        }

        return new StaviaEvidence(
                StaviaEvidenceTypes.PDOC,
                "PDOC:" + snapshot.id(),
                buildSummary(snapshot, obra),
                instant(snapshot.executedAt()),
                true,
                attributes
        );
    }

    private Map<String, Object> baseAttributes(Obra obra) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put("obraId", obra.getId());
        putText(attributes, "codigoContrato", obra.getCodigoContrato());
        putText(attributes, "codigoCw", obra.getCodigoCw());
        putText(attributes, "codigoInterno", obra.getCodigoInterno());
        putText(attributes, "nomeObra", obra.getNome());

        return attributes;
    }

    private String buildSummary(
            PdocSnapshot snapshot,
            Obra obra
    ) {
        String code = obraCode(obra);
        String reference =
                snapshot.referenceDate() == null
                        ? "sem data de referência"
                        : "referência "
                                + DATE_FORMAT.format(
                                        snapshot.referenceDate()
                                );

        if (
                snapshot.executionStatus()
                == PdocExecutionStatus.INSUFFICIENT_DATA
        ) {
            return "Snapshot PDOC da obra "
                    + code
                    + " com dados insuficientes, "
                    + reference
                    + ".";
        }

        if (
                snapshot.executionStatus()
                == PdocExecutionStatus.FAILED
        ) {
            return "Execução PDOC da obra "
                    + code
                    + " falhou, "
                    + reference
                    + ".";
        }

        StringBuilder summary =
                new StringBuilder();

        summary.append("Snapshot PDOC calculado para a obra ")
                .append(code)
                .append(", ")
                .append(reference);

        if (snapshot.riskLevel() != null) {
            summary.append(", risco ")
                    .append(snapshot.riskLevel());
        }

        if (snapshot.costP50() != null) {
            summary.append(", P50 ")
                    .append(snapshot.costP50());
        }

        summary.append(".");
        return summary.toString();
    }

    private List<String> jsonTextList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();

        node.forEach(item -> {
            if (!item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        });

        return List.copyOf(values);
    }

    private List<String> missingRequiredFields(JsonNode origins) {
        if (origins == null || !origins.isObject()) {
            return List.of();
        }

        List<String> missing = new ArrayList<>();

        origins.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            boolean required =
                    value.path("required").asBoolean(false);
            String availability =
                    value.path("availability").asText("");

            if (
                    required
                    && (
                            "ABSENT".equals(availability)
                            || "AMBIGUOUS".equals(availability)
                    )
            ) {
                missing.add(entry.getKey());
            }
        });

        return List.copyOf(missing);
    }

    private String obraCode(Obra obra) {
        if (hasText(obra.getCodigoCw())) {
            return obra.getCodigoCw().trim();
        }

        if (hasText(obra.getCodigoContrato())) {
            return obra.getCodigoContrato().trim();
        }

        if (hasText(obra.getCodigoInterno())) {
            return obra.getCodigoInterno().trim();
        }

        return obra.getId();
    }

    private void putText(
            Map<String, Object> attributes,
            String key,
            String value
    ) {
        if (hasText(value)) {
            attributes.put(key, value.trim());
        }
    }

    private void putDate(
            Map<String, Object> attributes,
            String key,
            LocalDate value
    ) {
        if (value != null) {
            attributes.put(key, value.toString());
        }
    }

    private void putDateTime(
            Map<String, Object> attributes,
            String key,
            LocalDateTime value
    ) {
        if (value != null) {
            attributes.put(key, value.toString());
        }
    }

    private void putDecimal(
            Map<String, Object> attributes,
            String key,
            BigDecimal value
    ) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private void putBoolean(
            Map<String, Object> attributes,
            String key,
            Boolean value
    ) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private void putInteger(
            Map<String, Object> attributes,
            String key,
            Integer value
    ) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private java.time.Instant instant(LocalDateTime value) {
        return value == null
                ? null
                : value.toInstant(ZoneOffset.UTC);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

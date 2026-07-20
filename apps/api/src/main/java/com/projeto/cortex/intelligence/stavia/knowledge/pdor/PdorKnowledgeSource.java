package com.projeto.cortex.intelligence.stavia.knowledge.pdor;

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
import com.projeto.cortex.pdor.PdorExecutionStatus;
import com.projeto.cortex.pdor.PdorSnapshot;
import com.projeto.cortex.pdor.PdorSnapshotRepository;

@Component
public class PdorKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PdorSnapshotRepository snapshotRepository;
    private final ObraRepository obraRepository;

    public PdorKnowledgeSource(
            PdorSnapshotRepository snapshotRepository,
            ObraRepository obraRepository
    ) {
        this.snapshotRepository = snapshotRepository;
        this.obraRepository = obraRepository;
    }

    @Override
    public String sourceName() {
        return "pdor";
    }

    @Override
    public String sourceVersion() {
        return StaviaVersions.PDOR_SOURCE;
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
                == StaviaIntent.CONSULTAR_PDOR;
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
                StaviaEvidenceTypes.PDOR,
                "PDOR:OBRA_NAO_LOCALIZADA:" + worksiteId,
                "O PDOR não localizou a obra informada no cadastro.",
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
                StaviaEvidenceTypes.PDOR,
                "PDOR:NO_SNAPSHOT:" + obra.getId(),
                "Nenhum snapshot PDOR encontrado para a obra "
                        + obraCode(obra)
                        + ".",
                null,
                false,
                attributes
        );
    }

    private StaviaEvidence toEvidence(
            PdorSnapshot snapshot,
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
        putText(attributes, "versaoDados", snapshot.dataVersion());
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
        putDecimal(attributes, "revenueP10", snapshot.revenueP10());
        putDecimal(attributes, "revenueP50", snapshot.revenueP50());
        putDecimal(attributes, "revenueP80", snapshot.revenueP80());
        putDecimal(attributes, "revenueP95", snapshot.revenueP95());
        putDecimal(
                attributes,
                "probabilityBelowContract",
                snapshot.probabilityBelowContract()
        );
        putDecimal(
                attributes,
                "probabilityBelow95Pct",
                snapshot.probabilityBelow95Pct()
        );
        putDecimal(
                attributes,
                "probabilityBelow90Pct",
                snapshot.probabilityBelow90Pct()
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
        putText(attributes, "iniciadoPor", snapshot.initiatedBy());
        putText(attributes, "tipoIniciador", snapshot.initiatorType());
        putJson(attributes, "escopoAnalisado", snapshot.analysisScope());
        putJson(attributes, "janelaTemporal", snapshot.temporalWindow());
        putJson(attributes, "featuresUtilizadas", snapshot.featuresUsed());
        putJson(attributes, "dadosAusentes", snapshot.missingData());
        putJson(attributes, "limitacoes", snapshot.limitations());
        putJson(attributes, "alertasDerivados", snapshot.alerts());
        putJson(attributes, "recomendacoes", snapshot.recommendations());
        putJson(attributes, "comparacaoAnterior", snapshot.previousComparison());
        putJson(attributes, "evidencias", snapshot.evidence());

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
                StaviaEvidenceTypes.PDOR,
                "PDOR:" + snapshot.id(),
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
            PdorSnapshot snapshot,
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
                == PdorExecutionStatus.INSUFFICIENT_DATA
        ) {
            return "Snapshot PDOR da obra "
                    + code
                    + " com dados insuficientes, "
                    + reference
                    + ".";
        }

        if (
                snapshot.executionStatus()
                == PdorExecutionStatus.FAILED
        ) {
            return "Execução PDOR da obra "
                    + code
                    + " falhou, "
                    + reference
                    + ".";
        }

        StringBuilder summary =
                new StringBuilder();

        summary.append("Snapshot PDOR calculado para a obra ")
                .append(code)
                .append(", ")
                .append(reference);

        if (snapshot.riskLevel() != null) {
            summary.append(", risco ")
                    .append(snapshot.riskLevel());
        }

        if (snapshot.revenueP50() != null) {
            summary.append(", P50 ")
                    .append(snapshot.revenueP50());
        }

        if (
                hasText(snapshot.calibrationStatus())
                && !"CALIBRATED".equals(snapshot.calibrationStatus())
        ) {
            summary.append(", modelo não calibrado");
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

    private void putJson(
            Map<String, Object> attributes,
            String key,
            JsonNode value
    ) {
        if (value != null && !value.isNull()) {
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

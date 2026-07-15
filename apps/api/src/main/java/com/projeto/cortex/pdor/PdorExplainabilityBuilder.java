package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class PdorExplainabilityBuilder {

    private static final Set<String> MODEL_FEATURES = Set.of(
            "contractValue", "measuredRevenue", "validatedRevenue",
            "totalPlannedQuantity", "plannedExecutedQuantity",
            "actualExecutedQuantity", "expectedMaterialConsumption",
            "actualMaterialConsumption", "expectedProductivity",
            "actualProductivity", "equipmentDowntimeHours30d",
            "plannedEquipmentHours30d", "delayedRdos",
            "criticalOccurrences", "pendingSyncEvents", "hoursSinceLastSync",
            "productivityLossWeeklySeries", "materialOverconsumptionWeeklySeries"
    );

    private final ObjectMapper objectMapper;

    PdorExplainabilityBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    PdorExplainability build(
            PdorSnapshot snapshot,
            PdorInputBundle inputs,
            PdorSnapshot previous
    ) {
        return new PdorExplainability(
                analysisScope(snapshot, inputs),
                temporalWindow(inputs),
                featuresUsed(inputs),
                missingData(inputs),
                limitations(snapshot, inputs),
                alerts(snapshot, inputs),
                recommendations(snapshot, inputs),
                previousComparison(snapshot, previous),
                evidence(inputs)
        );
    }

    private JsonNode analysisScope(PdorSnapshot snapshot, PdorInputBundle inputs) {
        ObjectNode scope = objectMapper.createObjectNode();
        scope.put("obraId", inputs.obraId());
        scope.put("codigoObra", inputs.codigoObra());
        scope.put("dataReferencia", inputs.referenceDate().toString());
        scope.put("objetivo", "PREVISAO_RECEITA_FINAL_E_RISCO_CONTRATUAL");
        scope.put("modoCalculo", snapshot.calculationMode());

        Set<String> sources = new TreeSet<>();
        inputs.origins().values().forEach(origin -> {
            if (origin.source() != null && !origin.source().isBlank()) {
                sources.add(origin.source());
            }
        });
        scope.set("fontesAvaliadas", objectMapper.valueToTree(sources));
        scope.set("dominiosContextuais", objectMapper.valueToTree(List.of(
                "CONTRATO", "RECEITA", "PROGRAMACAO", "RDO", "MATERIAL",
                "EQUIPAMENTO", "OCORRENCIA", "SINCRONIZACAO", "EQUIPE",
                "GEOGRAFIA"
        )));
        return scope;
    }

    private JsonNode temporalWindow(PdorInputBundle inputs) {
        ObjectNode window = objectMapper.createObjectNode();
        putValue(window, "inicioProgramacao", inputs.inputs().get("scheduleStartDate"));
        putValue(window, "fimProgramacao", inputs.inputs().get("scheduleEndDate"));
        window.put("dataReferencia", inputs.referenceDate().toString());
        window.put("janelaEquipamentosDias", 30);
        window.put("serieHistoricaSemanal", true);
        return window;
    }

    private JsonNode featuresUsed(PdorInputBundle inputs) {
        ArrayNode features = objectMapper.createArrayNode();
        inputs.origins().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    PdorInputOrigin origin = entry.getValue();
                    ObjectNode feature = objectMapper.createObjectNode();
                    feature.put("campo", entry.getKey());
                    feature.put("rotulo", origin.label());
                    feature.put("fonte", origin.source());
                    feature.put("disponibilidade", origin.availability().name());
                    feature.put("obrigatorio", origin.required());
                    feature.put(
                            "uso",
                            MODEL_FEATURES.contains(entry.getKey())
                                    ? origin.availability() == PdorDataAvailability.AMBIGUOUS
                                            ? "MODELO_COM_RESTRICAO"
                                            : "MODELO"
                                    : "CONTEXTO_EXPLICATIVO"
                    );
                    feature.put("utilizado", origin.value() != null
                            && origin.availability() != PdorDataAvailability.ABSENT);
                    features.add(feature);
                });
        return features;
    }

    private JsonNode missingData(PdorInputBundle inputs) {
        ArrayNode missing = objectMapper.createArrayNode();
        inputs.origins().entrySet().stream()
                .filter(entry -> entry.getValue().availability() == PdorDataAvailability.ABSENT
                        || entry.getValue().availability() == PdorDataAvailability.AMBIGUOUS)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    PdorInputOrigin origin = entry.getValue();
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("campo", entry.getKey());
                    item.put("rotulo", origin.label());
                    item.put("fonteEsperada", origin.source());
                    item.put("disponibilidade", origin.availability().name());
                    item.put("obrigatorio", origin.required());
                    item.put("detalhe", origin.detail());
                    missing.add(item);
                });
        return missing;
    }

    private JsonNode limitations(PdorSnapshot snapshot, PdorInputBundle inputs) {
        ArrayNode limitations = objectMapper.createArrayNode();
        LinkedHashSet<String> descriptions = new LinkedHashSet<>(inputs.warnings());
        if (!"CALIBRATED".equals(snapshot.calibrationStatus())) {
            descriptions.add(
                    "O modelo não está calibrado com histórico suficiente; probabilidades usam premissas de protótipo."
            );
        }
        if (snapshot.simulationConverged() != null && !snapshot.simulationConverged()) {
            descriptions.add("A simulação probabilística não atingiu o critério de convergência.");
        }
        int index = 0;
        for (String description : descriptions) {
            ObjectNode limitation = objectMapper.createObjectNode();
            limitation.put("codigo", "LIMITACAO_" + (++index));
            limitation.put("descricao", description);
            limitations.add(limitation);
        }
        return limitations;
    }

    private JsonNode alerts(PdorSnapshot snapshot, PdorInputBundle inputs) {
        ArrayNode alerts = objectMapper.createArrayNode();
        if (snapshot.executionStatus() != PdorExecutionStatus.SUCCESS) {
            addCodedItem(
                    alerts,
                    "EXECUCAO_" + snapshot.executionStatus().name(),
                    snapshot.executionStatus().label(),
                    snapshot.executionError()
            );
        }
        if ("HIGH".equals(snapshot.riskLevel()) || "CRITICAL".equals(snapshot.riskLevel())) {
            addCodedItem(
                    alerts, "RISCO_RECEITA_ELEVADO", "Risco de receita elevado",
                    "O nível de risco calculado foi " + snapshot.riskLevel() + "."
            );
        }
        addPositiveCountAlert(
                alerts, inputs, "delayedRdos", "RDOS_ATRASADOS",
                "Há datas programadas sem RDO correspondente."
        );
        addPositiveCountAlert(
                alerts, inputs, "pendingSyncEvents", "SINCRONIZACAO_PENDENTE",
                "Há mutações da obra ainda pendentes de sincronização."
        );
        if (number(inputs.inputs().get("activeTeamCount")) != null
                && number(inputs.inputs().get("activeTeamCount")).signum() == 0) {
            addCodedItem(
                    alerts, "EQUIPE_NAO_DEFINIDA", "Equipe não definida",
                    "Nenhuma equipe ativa foi encontrada para a obra na data de referência."
            );
        } else if (number(inputs.inputs().get("responsibleTeamMemberCount")) != null
                && number(inputs.inputs().get("responsibleTeamMemberCount")).signum() == 0) {
            addCodedItem(
                    alerts, "RESPONSAVEL_NAO_DEFINIDO", "Responsável não definido",
                    "As equipes ativas não possuem membro responsável na data de referência."
            );
        }
        return alerts;
    }

    private JsonNode recommendations(PdorSnapshot snapshot, PdorInputBundle inputs) {
        ArrayNode recommendations = objectMapper.createArrayNode();
        for (String field : inputs.missingRequiredFields()) {
            PdorInputOrigin origin = inputs.origins().get(field);
            addRecommendation(
                    recommendations,
                    "COMPLETAR_" + field.toUpperCase(),
                    "Completar dado obrigatório",
                    origin == null ? field : origin.label(),
                    List.of(field)
            );
        }
        if (positive(inputs.inputs().get("delayedRdos"))) {
            addRecommendation(
                    recommendations, "REGULARIZAR_RDOS", "Regularizar RDOs pendentes",
                    "Registrar ou justificar as datas programadas sem RDO.",
                    List.of("delayedRdos")
            );
        }
        if (positive(inputs.inputs().get("pendingSyncEvents"))) {
            addRecommendation(
                    recommendations, "CONCLUIR_SINCRONIZACAO", "Concluir sincronização",
                    "Sincronizar as mutações pendentes antes de usar a previsão para decisão.",
                    List.of("pendingSyncEvents", "hoursSinceLastSync")
            );
        }
        if (number(inputs.inputs().get("activeTeamCount")) != null
                && number(inputs.inputs().get("activeTeamCount")).signum() == 0) {
            addRecommendation(
                    recommendations, "DEFINIR_EQUIPE", "Definir equipe da obra",
                    "Associar uma equipe real para melhorar a leitura de capacidade operacional.",
                    List.of("activeTeamCount")
            );
        }
        if (!"CALIBRATED".equals(snapshot.calibrationStatus())) {
            addRecommendation(
                    recommendations, "AMPLIAR_HISTORICO", "Ampliar histórico operacional",
                    "Acumular ao menos quatro semanas consistentes de produtividade e materiais.",
                    List.of("productivityLossWeeklySeries", "materialOverconsumptionWeeklySeries")
            );
        }
        if ("HIGH".equals(snapshot.riskLevel()) || "CRITICAL".equals(snapshot.riskLevel())) {
            addRecommendation(
                    recommendations, "REVISAR_DRIVERS", "Revisar fatores de risco",
                    "Priorizar os drivers registrados no snapshot e reprogramar somente com evidência validada.",
                    List.of("drivers")
            );
        }
        return recommendations;
    }

    private JsonNode previousComparison(PdorSnapshot current, PdorSnapshot previous) {
        ObjectNode comparison = objectMapper.createObjectNode();
        if (previous == null) {
            comparison.put("disponivel", false);
            comparison.put("motivo", "Não há execução anterior para esta obra.");
            comparison.set("inputsAlterados", objectMapper.createArrayNode());
            return comparison;
        }

        comparison.put("disponivel", true);
        comparison.put("snapshotAnteriorId", previous.id());
        comparison.put("dataExecucaoAnterior", String.valueOf(previous.executedAt()));
        comparison.put("direcaoRisco", riskDirection(current, previous));
        comparison.set("probabilidadeAbaixoContrato", numericChange(
                previous.probabilityBelowContract(), current.probabilityBelowContract()
        ));
        comparison.set("receitaP50", numericChange(previous.revenueP50(), current.revenueP50()));
        comparison.set("confianca", numericChange(previous.confidence(), current.confidence()));
        comparison.set("scoreHeuristico", numericChange(
                previous.heuristicRiskScore(), current.heuristicRiskScore()
        ));
        comparison.set("inputsAlterados", changedInputs(previous.inputs(), current.inputs()));
        return comparison;
    }

    private JsonNode evidence(PdorInputBundle inputs) {
        ArrayNode evidence = objectMapper.createArrayNode();
        for (PdorEvidenceReference reference : inputs.evidenceReferences()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("tipoEntidade", reference.entityType());
            item.put("entidadeId", reference.entityId());
            item.put("fonte", reference.source());
            item.put("papel", reference.role());
            if (reference.observedAt() != null) {
                item.put("observadoEm", reference.observedAt().toString());
            }
            evidence.add(item);
        }
        return evidence;
    }

    private ArrayNode changedInputs(JsonNode previous, JsonNode current) {
        ArrayNode changes = objectMapper.createArrayNode();
        if (previous == null || !previous.isObject() || current == null || !current.isObject()) {
            return changes;
        }
        Set<String> keys = new TreeSet<>();
        previous.fieldNames().forEachRemaining(keys::add);
        current.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            JsonNode before = previous.get(key);
            JsonNode after = current.get(key);
            if (!java.util.Objects.equals(before, after)) {
                ObjectNode change = objectMapper.createObjectNode();
                change.put("campo", key);
                change.set("antes", before == null ? objectMapper.nullNode() : before);
                change.set("depois", after == null ? objectMapper.nullNode() : after);
                changes.add(change);
            }
        }
        return changes;
    }

    private ObjectNode numericChange(BigDecimal before, BigDecimal after) {
        ObjectNode change = objectMapper.createObjectNode();
        putDecimal(change, "antes", before);
        putDecimal(change, "depois", after);
        putDecimal(change, "delta", before == null || after == null ? null : after.subtract(before));
        return change;
    }

    private String riskDirection(PdorSnapshot current, PdorSnapshot previous) {
        BigDecimal currentValue = current.probabilityBelowContract() != null
                ? current.probabilityBelowContract()
                : current.heuristicRiskScore();
        BigDecimal previousValue = previous.probabilityBelowContract() != null
                ? previous.probabilityBelowContract()
                : previous.heuristicRiskScore();
        if (currentValue == null || previousValue == null) return "NAO_COMPARAVEL";
        int comparison = currentValue.compareTo(previousValue);
        return comparison > 0 ? "SUBIU" : comparison < 0 ? "CAIU" : "ESTAVEL";
    }

    private void addPositiveCountAlert(
            ArrayNode target,
            PdorInputBundle inputs,
            String field,
            String code,
            String description
    ) {
        if (positive(inputs.inputs().get(field))) {
            addCodedItem(target, code, field, description);
        }
    }

    private void addCodedItem(ArrayNode target, String code, String title, String detail) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("codigo", code);
        item.put("titulo", title);
        item.put("detalhe", detail);
        target.add(item);
    }

    private void addRecommendation(
            ArrayNode target,
            String code,
            String title,
            String reason,
            List<String> evidenceFields
    ) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("codigo", code);
        item.put("titulo", title);
        item.put("motivo", reason);
        item.set("camposEvidencia", objectMapper.valueToTree(evidenceFields));
        target.add(item);
    }

    private void putValue(ObjectNode target, String field, Object value) {
        if (value != null) {
            target.set(field, objectMapper.valueToTree(value));
        } else {
            target.putNull(field);
        }
    }

    private void putDecimal(ObjectNode target, String field, BigDecimal value) {
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }

    private boolean positive(Object value) {
        BigDecimal number = number(value);
        return number != null && number.signum() > 0;
    }

    private BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return null;
    }
}

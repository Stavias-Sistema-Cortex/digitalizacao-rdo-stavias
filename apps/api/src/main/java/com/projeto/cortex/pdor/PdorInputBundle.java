package com.projeto.cortex.pdor;

import com.projeto.cortex.intelligence.PdorContextBuilder;
import com.projeto.cortex.intelligence.PdorEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public record PdorInputBundle(
        String obraId,
        String codigoObra,
        LocalDate referenceDate,
        Map<String, Object> inputs,
        Map<String, PdorInputOrigin> origins,
        List<String> warnings,
        List<String> missingRequiredFields,
        SourceValues sourceValues,
        PdorEngine.HistoricalSeries historicalSeries,
        List<PdorEvidenceReference> evidenceReferences,
        List<String> revenueEvidenceIds,
        long evidenceHighWaterMark,
        String revenueCoverageCode
) {
    public PdorInputBundle {
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        origins = Collections.unmodifiableMap(new LinkedHashMap<>(origins));
        warnings = List.copyOf(warnings);
        missingRequiredFields = List.copyOf(missingRequiredFields);
        historicalSeries = historicalSeries == null
                ? PdorEngine.HistoricalSeries.EMPTY
                : historicalSeries;
        evidenceReferences = evidenceReferences == null
                ? List.of()
                : List.copyOf(evidenceReferences);
        revenueEvidenceIds = revenueEvidenceIds == null
                ? List.of()
                : List.copyOf(new TreeSet<>(revenueEvidenceIds));
        if (evidenceHighWaterMark < 0) {
            throw new IllegalArgumentException(
                    "evidenceHighWaterMark não pode ser negativo."
            );
        }
        revenueCoverageCode = revenueCoverageCode == null
                || revenueCoverageCode.isBlank()
                ? "NO_ACCEPTED_EVIDENCE"
                : revenueCoverageCode;
    }

    public PdorInputBundle(
            String obraId,
            String codigoObra,
            LocalDate referenceDate,
            Map<String, Object> inputs,
            Map<String, PdorInputOrigin> origins,
            List<String> warnings,
            List<String> missingRequiredFields,
            SourceValues sourceValues,
            PdorEngine.HistoricalSeries historicalSeries,
            List<PdorEvidenceReference> evidenceReferences
    ) {
        this(
                obraId, codigoObra, referenceDate, inputs, origins, warnings,
                missingRequiredFields, sourceValues, historicalSeries,
                evidenceReferences, List.of(), 0L, "NO_ACCEPTED_EVIDENCE"
        );
    }

    public PdorInputBundle(
            String obraId,
            String codigoObra,
            LocalDate referenceDate,
            Map<String, Object> inputs,
            Map<String, PdorInputOrigin> origins,
            List<String> warnings,
            List<String> missingRequiredFields,
            SourceValues sourceValues,
            PdorEngine.HistoricalSeries historicalSeries
    ) {
        this(
                obraId, codigoObra, referenceDate, inputs, origins, warnings,
                missingRequiredFields, sourceValues, historicalSeries, List.of()
        );
    }

    public boolean canCalculate() {
        return missingRequiredFields.isEmpty();
    }

    public Map<String, Object> originsAsMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        origins.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().toMap()));
        return result;
    }

    public PdorContextBuilder.PdorSourceSnapshot toSourceSnapshot() {
        if (!canCalculate()) {
            throw new IllegalStateException(
                    "Dados insuficientes para montar o contexto do PDOR."
            );
        }

        return new PdorContextBuilder.PdorSourceSnapshot(
                obraId,
                referenceDate,
                sourceValues.contractValue(),
                sourceValues.measuredRevenue(),
                sourceValues.validatedRevenue(),
                sourceValues.totalPlannedQuantity(),
                sourceValues.plannedExecutedQuantity(),
                sourceValues.actualExecutedQuantity(),
                sourceValues.expectedMaterialConsumption(),
                sourceValues.actualMaterialConsumption(),
                sourceValues.expectedProductivity(),
                sourceValues.actualProductivity(),
                sourceValues.equipmentDowntimeHours30d(),
                sourceValues.plannedEquipmentHours30d(),
                sourceValues.delayedRdos(),
                sourceValues.criticalOccurrences(),
                sourceValues.pendingSyncEvents(),
                sourceValues.hoursSinceLastSync(),
                sourceValues.hasContractData(),
                sourceValues.hasScheduleData(),
                sourceValues.hasExecutionData(),
                sourceValues.hasMaterialData(),
                sourceValues.hasEquipmentData(),
                sourceValues.hasRdoData(),
                sourceValues.hasOccurrenceData(),
                sourceValues.hasSyncMetadata(),
                sourceValues.contractValidated(),
                sourceValues.scheduleValidated(),
                sourceValues.quantitiesValidated(),
                sourceValues.simulationIterations()
        );
    }

    public record SourceValues(
            BigDecimal contractValue,
            BigDecimal measuredRevenue,
            BigDecimal validatedRevenue,
            double totalPlannedQuantity,
            double plannedExecutedQuantity,
            double actualExecutedQuantity,
            double expectedMaterialConsumption,
            double actualMaterialConsumption,
            double expectedProductivity,
            double actualProductivity,
            double equipmentDowntimeHours30d,
            double plannedEquipmentHours30d,
            int delayedRdos,
            int criticalOccurrences,
            int pendingSyncEvents,
            int hoursSinceLastSync,
            boolean hasContractData,
            boolean hasScheduleData,
            boolean hasExecutionData,
            boolean hasMaterialData,
            boolean hasEquipmentData,
            boolean hasRdoData,
            boolean hasOccurrenceData,
            boolean hasSyncMetadata,
            boolean contractValidated,
            boolean scheduleValidated,
            boolean quantitiesValidated,
            int simulationIterations
    ) {
    }
}

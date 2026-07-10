package com.projeto.cortex.pdor;

import com.projeto.cortex.intelligence.PdorContextBuilder;
import com.projeto.cortex.intelligence.PdorEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PdorInputBundle(
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
    public PdorInputBundle {
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        origins = Collections.unmodifiableMap(new LinkedHashMap<>(origins));
        warnings = List.copyOf(warnings);
        missingRequiredFields = List.copyOf(missingRequiredFields);
        historicalSeries = historicalSeries == null
                ? PdorEngine.HistoricalSeries.EMPTY
                : historicalSeries;
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

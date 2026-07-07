package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PdorSnapshot(
        String id,
        String obraId,
        String codigoObra,
        LocalDateTime executedAt,
        LocalDate referenceDate,
        String modelVersion,
        String assumptionsVersion,
        PdorExecutionStatus executionStatus,
        PdorTriggerType triggerType,
        String originEventId,
        String idempotencyKey,
        JsonNode inputs,
        JsonNode inputOrigins,
        JsonNode warnings,
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
        String executionError,
        LocalDateTime createdAt
) {
}

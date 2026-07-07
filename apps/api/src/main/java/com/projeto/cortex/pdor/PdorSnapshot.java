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
        BigDecimal costP10,
        BigDecimal costP50,
        BigDecimal costP80,
        BigDecimal costP95,
        BigDecimal racRci,
        BigDecimal racRciSpi,
        BigDecimal racBottomUp,
        BigDecimal racWeighted,
        BigDecimal rci,
        BigDecimal spi,
        BigDecimal probabilityAnyOverrun,
        BigDecimal probabilityOverFivePercent,
        BigDecimal probabilityOverTenPercent,
        BigDecimal heuristicRiskScore,
        BigDecimal confidence,
        Boolean simulationConverged,
        Integer simulationIterations,
        JsonNode drivers,
        String executionError,
        LocalDateTime createdAt
) {
}

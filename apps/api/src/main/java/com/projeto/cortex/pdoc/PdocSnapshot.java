package com.projeto.cortex.pdoc;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PdocSnapshot(
        String id,
        String obraId,
        String codigoObra,
        LocalDateTime executedAt,
        LocalDate referenceDate,
        String modelVersion,
        String assumptionsVersion,
        PdocExecutionStatus executionStatus,
        PdocTriggerType triggerType,
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
        String executionError,
        LocalDateTime createdAt
) {
}

package com.projeto.cortex.pdoc;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record PdocResultadoResponse(
        String id,
        ObraResumo obra,
        LocalDate dataReferencia,
        LocalDateTime dataExecucao,
        String versaoModelo,
        String versaoPremissas,
        String statusExecucao,
        String statusExecucaoLabel,
        String tipoDisparo,
        String tipoDisparoLabel,
        String calibracao,
        String calibracaoLabel,
        String fase,
        String faseLabel,
        String risco,
        String riscoLabel,
        BigDecimal custoEstimadoFinal,
        Map<String, BigDecimal> eacs,
        BigDecimal p10,
        BigDecimal p50,
        BigDecimal p80,
        BigDecimal p95,
        BigDecimal probabilidadeQualquerExcedente,
        BigDecimal probabilidadeExceder5Pct,
        BigDecimal probabilidadeExceder10Pct,
        BigDecimal scoreHeuristico,
        BigDecimal confianca,
        Boolean simulacaoConvergiu,
        Integer iteracoesSimulacao,
        JsonNode drivers,
        JsonNode warnings,
        JsonNode origemDados,
        JsonNode inputs,
        String erroExecucao,
        boolean snapshotExistente
) {
    public static PdocResultadoResponse from(
            PdocSnapshot snapshot,
            ObraResumo obra,
            boolean snapshotExistente
    ) {
        Map<String, BigDecimal> eacs = new LinkedHashMap<>();
        eacs.put("cpi", snapshot.eacCpi());
        eacs.put("cpiSpi", snapshot.eacCpiSpi());
        eacs.put("bottomUp", snapshot.eacBottomUp());
        eacs.put("ponderado", snapshot.eacWeighted());

        return new PdocResultadoResponse(
                snapshot.id(),
                obra,
                snapshot.referenceDate(),
                snapshot.executedAt(),
                snapshot.modelVersion(),
                snapshot.assumptionsVersion(),
                snapshot.executionStatus().name(),
                snapshot.executionStatus().label(),
                snapshot.triggerType().name(),
                snapshot.triggerType().label(),
                snapshot.calibrationStatus(),
                calibrationLabel(snapshot.calibrationStatus()),
                snapshot.projectPhase(),
                phaseLabel(snapshot.projectPhase()),
                snapshot.riskLevel(),
                riskLabel(snapshot.riskLevel()),
                snapshot.costP50(),
                eacs,
                snapshot.costP10(),
                snapshot.costP50(),
                snapshot.costP80(),
                snapshot.costP95(),
                snapshot.probabilityAnyOverrun(),
                snapshot.probabilityOverFivePercent(),
                snapshot.probabilityOverTenPercent(),
                snapshot.heuristicRiskScore(),
                snapshot.confidence(),
                snapshot.simulationConverged(),
                snapshot.simulationIterations(),
                snapshot.drivers(),
                snapshot.warnings(),
                snapshot.inputOrigins(),
                snapshot.inputs(),
                snapshot.executionError(),
                snapshotExistente
        );
    }

    private static String calibrationLabel(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "NOT_CALIBRATED" -> "Não calibrado";
            case "CALIBRATION_IN_PROGRESS" -> "Calibração em andamento";
            case "CALIBRATED" -> "Calibrado";
            default -> value;
        };
    }

    private static String phaseLabel(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "INITIAL" -> "Inicial";
            case "PRODUCTION" -> "Produção";
            case "ADVANCED" -> "Avançada";
            case "CLOSING" -> "Encerramento";
            default -> value;
        };
    }

    private static String riskLabel(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "LOW" -> "Baixo";
            case "MODERATE" -> "Moderado";
            case "HIGH" -> "Alto";
            case "CRITICAL" -> "Crítico";
            default -> value;
        };
    }

    public record ObraResumo(
            String id,
            String codigoContrato,
            String codigoCw,
            String codigoInterno,
            String nome
    ) {
    }
}

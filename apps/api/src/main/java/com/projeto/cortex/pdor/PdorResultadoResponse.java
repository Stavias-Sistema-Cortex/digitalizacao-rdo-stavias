package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record PdorResultadoResponse(
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
        BigDecimal receitaEstimadaFinal,
        Map<String, BigDecimal> racs,
        BigDecimal p10,
        BigDecimal p50,
        BigDecimal p80,
        BigDecimal p95,
        BigDecimal probabilidadeAbaixoContrato,
        BigDecimal probabilidadeAbaixo95Pct,
        BigDecimal probabilidadeAbaixo90Pct,
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
    public static PdorResultadoResponse from(
            PdorSnapshot snapshot,
            ObraResumo obra,
            boolean snapshotExistente
    ) {
        Map<String, BigDecimal> racs = new LinkedHashMap<>();
        racs.put("rci", snapshot.racRci());
        racs.put("rciSpi", snapshot.racRciSpi());
        racs.put("bottomUp", snapshot.racBottomUp());
        racs.put("ponderado", snapshot.racWeighted());

        return new PdorResultadoResponse(
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
                snapshot.revenueP50(),
                racs,
                snapshot.revenueP10(),
                snapshot.revenueP50(),
                snapshot.revenueP80(),
                snapshot.revenueP95(),
                snapshot.probabilityBelowContract(),
                snapshot.probabilityBelow95Pct(),
                snapshot.probabilityBelow90Pct(),
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

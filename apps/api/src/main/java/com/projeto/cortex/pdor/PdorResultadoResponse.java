package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.projeto.cortex.financeiro.ExactDecimalJsonSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
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
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal receitaEstimadaFinal,
        /*
         * A receita prevista final é o RAC ponderado — a mesma cifra que o
         * bloco de evidências publica. Ela sai aqui como campo próprio porque
         * o histórico é lido por máquina, e cavar dentro de `racs` para montar
         * um gráfico é acoplar o consumidor à ordem interna do mapa.
         */
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal receitaPrevistaFinal,
        /*
         * As três medidas físicas que o gráfico da Home acompanha, elevadas de
         * dentro de `inputs` para o corpo da resposta. Produção realizada é a
         * que tem receita aceita; produção apontada é a que o RDO declarou,
         * sem preço nem validação. Elas divergem de propósito.
         */
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal producaoPlanejada,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal producaoRealizada,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal producaoApontada,
        @JsonSerialize(contentUsing = ExactDecimalJsonSerializer.class)
        Map<String, BigDecimal> racs,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal p10,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal p50,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal p80,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
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
        boolean snapshotExistente,
        String versaoDados,
        JsonNode escopoAnalisado,
        JsonNode janelaTemporal,
        JsonNode featuresUtilizadas,
        JsonNode dadosAusentes,
        JsonNode limitacoes,
        JsonNode alertasDerivados,
        JsonNode recomendacoes,
        JsonNode comparacaoAnterior,
        JsonNode evidencias,
        String iniciadoPor,
        String tipoIniciador,
        String algorithmVersion,
        List<String> evidenceIds,
        Long evidenceHighWaterMark,
        String coverageCode,
        JsonNode assumptions,
        Instant executedAtUtc,
        boolean stale,
        boolean current
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
                snapshot.racWeighted(),
                decimalInput(snapshot.inputs(), "totalPlannedQuantity"),
                decimalInput(snapshot.inputs(), "actualExecutedQuantity"),
                decimalInput(snapshot.inputs(), "reportedExecutedQuantity"),
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
                snapshotExistente,
                snapshot.dataVersion(),
                snapshot.analysisScope(),
                snapshot.temporalWindow(),
                snapshot.featuresUsed(),
                snapshot.missingData(),
                snapshot.limitations(),
                snapshot.alerts(),
                snapshot.recommendations(),
                snapshot.previousComparison(),
                snapshot.evidence(),
                snapshot.initiatedBy(),
                snapshot.initiatorType(),
                snapshot.algorithmVersion(),
                snapshot.evidenceIds(),
                snapshot.evidenceHighWaterMark(),
                snapshot.coverageCode(),
                snapshot.assumptions(),
                snapshot.executedAtUtc(),
                snapshot.stale(),
                snapshot.current()
        );
    }

    /**
     * Lê um número de dentro do mapa de entradas persistido no snapshot.
     *
     * As entradas do PDOR vivem em `inputs_json`, não em colunas: é lá que
     * cabe uma entrada nova sem migração. Ausência e não-número devolvem null
     * — nunca zero — porque zero é uma medida, e inventá-la aqui apagaria a
     * diferença entre "não mediu" e "mediu nada".
     */
    private static BigDecimal decimalInput(JsonNode inputs, String field) {
        if (inputs == null || !inputs.hasNonNull(field)) {
            return null;
        }
        JsonNode value = inputs.get(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static String calibrationLabel(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "NOT_CALIBRATED" -> "Protótipo";
            case "CALIBRATION_IN_PROGRESS" -> "Histórico assistido";
            case "CALIBRATED" -> "Calibrado, sem validação externa";
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

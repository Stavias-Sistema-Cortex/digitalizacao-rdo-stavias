package com.projeto.cortex.financeiro;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PrevisaoFinanceiraResponse(
        String id,
        String obraId,
        String codigoObra,
        LocalDate dataReferencia,
        LocalDateTime dataExecucao,
        String versaoModelo,
        String versaoPremissas,
        String statusExecucao,
        String statusExecucaoLabel,
        String statusCalculo,
        String statusCalculoLabel,
        BigDecimal receitaEstimadaDia,
        BigDecimal receitaEstimadaAcumulada,
        BigDecimal receitaValidadaAcumulada,
        BigDecimal receitaMedida,
        BigDecimal receitaAprovada,
        BigDecimal receitaFaturada,
        BigDecimal receitaRecebida,
        BigDecimal receitaPrevistaFinal,
        BigDecimal custoRealizado,
        BigDecimal custoComprometido,
        BigDecimal custoPrevistoFinal,
        BigDecimal margemAtual,
        BigDecimal margemPrevista,
        BigDecimal margemPercentual,
        BigDecimal producaoPlanejada,
        BigDecimal producaoRealizada,
        BigDecimal backlogContratual,
        BigDecimal receitaEmRisco,
        BigDecimal confianca,
        BigDecimal qualidadeDados,
        JsonNode warnings,
        JsonNode inputs,
        JsonNode fontes,
        String erroExecucao,
        boolean snapshotExistente
) {
    public static String statusExecucaoLabel(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "NAO_EXECUTADO" -> "Não executado";
            case "SEM_SNAPSHOT" -> "Sem snapshot";
            case "DADOS_INSUFICIENTES" -> "Dados insuficientes";
            case "CALCULADO" -> "Calculado";
            case "ERRO_DE_EXECUCAO" -> "Erro de execução";
            default -> status;
        };
    }

    public static String statusCalculoLabel(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "NAO_CALIBRADO" -> "Não calibrado";
            case "CALIBRADO" -> "Calibrado";
            case "DADOS_INSUFICIENTES" -> "Dados insuficientes";
            default -> status;
        };
    }
}

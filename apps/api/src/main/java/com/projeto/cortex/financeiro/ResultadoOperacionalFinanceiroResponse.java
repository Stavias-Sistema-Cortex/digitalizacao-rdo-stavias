package com.projeto.cortex.financeiro;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ResultadoOperacionalFinanceiroResponse(
        String obraId,
        LocalDate de,
        LocalDate ate,
        BigDecimal producaoRealizada,
        BigDecimal receitaOperacionalEstimada,
        BigDecimal custoRealizado,
        BigDecimal margemAtual,
        BigDecimal margemPercentual,
        BigDecimal receitaMedida,
        BigDecimal receitaAprovada,
        BigDecimal receitaFaturada,
        BigDecimal receitaRecebida,
        List<Servico> servicos,
        PrevisaoFinanceiraResponse pdor
) {
    public record Servico(
            String nome,
            String unidade,
            BigDecimal quantidadeExecutada,
            BigDecimal custoRealizado,
            BigDecimal receitaOperacionalEstimada,
            BigDecimal margem,
            BigDecimal margemPercentual,
            long quantidadeRdos
    ) { }
}


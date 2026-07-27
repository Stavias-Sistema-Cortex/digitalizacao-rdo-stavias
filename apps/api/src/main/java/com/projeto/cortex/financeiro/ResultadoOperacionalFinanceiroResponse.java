package com.projeto.cortex.financeiro;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.projeto.cortex.pdor.PdorResultadoResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ResultadoOperacionalFinanceiroResponse(
        String obraId,
        LocalDate de,
        LocalDate ate,
        String coverageCode,
        long evidenceCount,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal producaoRealizada,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal receitaOperacional,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal receitaMedida,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal receitaAprovada,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal receitaFaturada,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal receitaRecebida,
        List<Servico> servicos,
        PdorResultadoResponse pdor
) {
    public record Servico(
            String nome,
            String unidade,
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal quantidadeExecutada,
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal receita,
            long quantidadeRdos
    ) { }
}

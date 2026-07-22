package com.projeto.cortex.financeiro;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RastreioReceitaResponse(
        LocalDate de,
        LocalDate ate,
        Totais consolidado,
        List<Obra> obras,
        List<TipoServico> tiposServico
) {
    public record Totais(
            BigDecimal producao,
            BigDecimal custo,
            BigDecimal receitaEstimada,
            BigDecimal margem,
            BigDecimal receitaMedida,
            BigDecimal receitaAprovada,
            BigDecimal receitaFaturada,
            BigDecimal receitaRecebida
    ) { }

    public record Obra(String id, String nome, Totais totais) { }

    public record TipoServico(
            String nome,
            String unidade,
            Totais totais,
            List<ContribuicaoObra> obras,
            long quantidadeRdos,
            boolean receitaDisponivel
    ) { }

    public record ContribuicaoObra(
            String obraId,
            String obraNome,
            String itemContratualId,
            String codigoItemContratual,
            Totais totais,
            long quantidadeRdos,
            List<String> rdoIds,
            boolean receitaDisponivel
    ) { }
}


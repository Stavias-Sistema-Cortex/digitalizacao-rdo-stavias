package com.projeto.cortex.rdos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record RdoCreateRequest(
        String id,
        String obraId,
        String programacaoId,

        String numeroRdo,
        LocalDate dataRdo,

        String cliente,
        String contrato,
        String rodovia,
        String cidade,
        String uf,

        String kmInicialProgramado,
        String kmFinalProgramado,
        String kmInicialInterditado,
        String kmFinalInterditado,

        String turno,
        LocalTime horaInicio,
        LocalTime horaFim,

        String condicaoManha,
        String condicaoTarde,
        String condicaoNoite,
        BigDecimal pluviometriaMm,

        String observacoes,

        List<MaoObraItem> maoObra,
        List<EquipamentoItem> equipamentos,
        List<MaterialItem> materiais,
        List<ControleGeometricoItem> controlesGeometricos,
        List<ServicoExecutadoItem> servicosExecutados,
        List<AlocacaoColaboradorItem> alocacoesColaboradores
) {

    public record MaoObraItem(
            String colaboradorId,
            String nomeColaborador,
            String cargo,
            String tipoVinculo,
            BigDecimal quantidade,
            LocalTime horaInicio,
            LocalTime horaFim,
            String observacoes
    ) {
    }

    public record EquipamentoItem(
            String assetId,
            String prefixo,
            String descricao,
            String tipoEquipamento,
            String tipoVinculo,
            BigDecimal quantidade,
            LocalTime horaInicio,
            LocalTime horaFim,
            String observacoes
    ) {
    }

    public record MaterialItem(
            String materialNome,
            String unidade,
            BigDecimal quantidadePrevista,
            BigDecimal quantidadeUsinada,
            BigDecimal quantidadeAplicada,
            BigDecimal quantidadeSobra,
            String notaFiscal,
            String fornecedor,
            String observacoes
    ) {
    }

    public record ControleGeometricoItem(
            String subtrecho,
            String estacaInicial,
            String estacaFinal,
            String kmInicial,
            String kmFinal,
            BigDecimal comprimentoM,
            BigDecimal larguraM,
            BigDecimal espessura1Cm,
            BigDecimal espessura2Cm,
            BigDecimal espessura3Cm,
            BigDecimal densidade,
            String observacoes
    ) {
    }

    public record ServicoExecutadoItem(
            String servicoNome,
            String itemContratualId,
            BigDecimal quantidadeExecutada,
            String unidade,
            String trechoInicial,
            String trechoFinal,
            String localizacao,
            String turno,
            String statusValidacao,
            BigDecimal custoRealizado,
            Boolean retrabalho,
            Boolean producaoRejeitada,
            String observacoes
    ) {
    }

    public record AlocacaoColaboradorItem(
            String colaboradorId,
            String equipe,
            String servicoNome,
            LocalTime horaInicio,
            LocalTime horaFim,
            BigDecimal percentualDia,
            String turno,
            String funcao,
            String centroCusto,
            String tipoAlocacao,
            String fonte,
            String status,
            BigDecimal custoHora,
            String observacoes
    ) {
    }
}

package com.projeto.cortex.rdos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record RdoResponse(
        String id,
        String obraId,
        String programacaoId,

        String numeroRdo,
        LocalDate dataRdo,
        String diaSemana,

        String cliente,
        String contrato,
        String rodovia,
        String cidade,
        String uf,

        String turno,
        LocalTime horaInicio,
        LocalTime horaFim,

        String status,
        String observacoes,

        List<MaoObraItem> maoObra,
        List<EquipamentoItem> equipamentos,
        List<MaterialItem> materiais,
        List<ControleGeometricoItem> controlesGeometricos
) {

    public record MaoObraItem(
            String id,
            String colaboradorId,
            String nomeColaborador,
            String cargo,
            String tipoVinculo,
            BigDecimal quantidade
    ) {
    }

    public record EquipamentoItem(
            String id,
            String assetId,
            String prefixo,
            String descricao,
            String tipoEquipamento,
            String tipoVinculo,
            BigDecimal quantidade
    ) {
    }

    public record MaterialItem(
            String id,
            String materialNome,
            String unidade,
            BigDecimal quantidadePrevista,
            BigDecimal quantidadeUsinada,
            BigDecimal quantidadeAplicada,
            BigDecimal quantidadeSobra
    ) {
    }

    public record ControleGeometricoItem(
            String id,
            String subtrecho,
            BigDecimal comprimentoM,
            BigDecimal larguraM,
            BigDecimal espessuraMediaCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            BigDecimal densidade,
            BigDecimal massaTonelada
    ) {
    }
}

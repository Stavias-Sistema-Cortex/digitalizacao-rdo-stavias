package com.projeto.cortex.rdos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RdoContextResponse(
        ObraContexto obra,
        LocalDate data,
        List<ProgramacaoContexto> programacoes,
        List<ColaboradorContexto> colaboradores,
        List<EquipamentoContexto> equipamentos
) {

    public record ObraContexto(
            String id,
            String codigoContrato,
            String codigoCw,
            String nome,
            String cliente,
            String cidade,
            String uf,
            String rodovia,
            String status
    ) {
    }

    public record ProgramacaoContexto(
            String id,
            LocalDate dataProgramacao,
            String equipe,
            String encarregado,
            String engenheiro,
            String cliente,
            String servico,
            String tipoServico,
            String cidade,
            String uf,
            String rodovia,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal espessuraCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            String status
    ) {
    }

    public record ColaboradorContexto(
            String id,
            String codigoColaborador,
            String nome,
            String email,
            String nomeGrupo,
            String nomePerfil,
            String cpfMascarado
    ) {
    }

    public record EquipamentoContexto(
            String id,
            String codigoExterno,
            String nome,
            String categoria
    ) {
    }
}

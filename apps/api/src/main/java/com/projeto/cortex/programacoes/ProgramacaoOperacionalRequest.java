package com.projeto.cortex.programacoes;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProgramacaoOperacionalRequest(
        String obraId,
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
        String status,
        String fonteCriacao,
        String fonteArquivo,
        Integer linhaOrigem,
        String observacoes
) {
}

package com.projeto.cortex.programacoes;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProgramacaoOperacionalRequest(
        String obraId,
        LocalDate dataProgramacao,
        String equipe,
        String fechamento,
        String encarregado,
        String encarregadoColaboradorId,
        String engenheiro,
        String cliente,
        String servico,
        String tipoServico,
        String cidade,
        String uf,
        String rodovia,
        String sentido,
        String periodo,
        String faixa,
        String kmInicial,
        String kmFinal,
        BigDecimal extensaoM,
        BigDecimal larguraM,
        BigDecimal espessuraCm,
        BigDecimal areaM2,
        BigDecimal volumeM3,
        BigDecimal toneladaMassa,
        String tipoCap,
        BigDecimal teorCapProjeto,
        BigDecimal cap,
        String status,
        String fonteCriacao,
        String fonteArquivo,
        Integer linhaOrigem,
        String observacoes
) {
}

package com.projeto.cortex.programacoes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ProgramacaoOperacionalResponse(
        String id,
        String obraId,
        String codigoContratoOrigem,
        String obraNomeOrigem,
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
        String observacoes,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
    public static ProgramacaoOperacionalResponse from(ProgramacaoOperacional programacao) {
        return new ProgramacaoOperacionalResponse(
                programacao.getId(),
                programacao.getObraId(),
                programacao.getCodigoContratoOrigem(),
                programacao.getObraNomeOrigem(),
                programacao.getDataProgramacao(),
                programacao.getEquipe(),
                programacao.getEncarregado(),
                programacao.getEngenheiro(),
                programacao.getCliente(),
                programacao.getServico(),
                programacao.getTipoServico(),
                programacao.getCidade(),
                programacao.getUf(),
                programacao.getRodovia(),
                programacao.getSentido(),
                programacao.getFaixa(),
                programacao.getKmInicial(),
                programacao.getKmFinal(),
                programacao.getExtensaoM(),
                programacao.getLarguraM(),
                programacao.getEspessuraCm(),
                programacao.getAreaM2(),
                programacao.getVolumeM3(),
                programacao.getStatus(),
                programacao.getFonteCriacao(),
                programacao.getFonteArquivo(),
                programacao.getLinhaOrigem(),
                programacao.getObservacoes(),
                programacao.getCriadoEm(),
                programacao.getAtualizadoEm()
        );
    }
}

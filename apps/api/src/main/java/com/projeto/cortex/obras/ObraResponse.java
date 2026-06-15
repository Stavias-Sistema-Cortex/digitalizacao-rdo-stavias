package com.projeto.cortex.obras;

import java.time.LocalDateTime;

public record ObraResponse(
        String id,
        String codigoContrato,
        String codigoCw,
        String codigoInterno,
        String nome,
        String cliente,
        String descricao,
        String cidade,
        String uf,
        String rodovia,
        String status,
        String fonteCriacao,
        String fonteArquivo,
        String observacoes,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
    public static ObraResponse from(Obra obra) {
        return new ObraResponse(
                obra.getId(),
                obra.getCodigoContrato(),
                obra.getCodigoCw(),
                obra.getCodigoInterno(),
                obra.getNome(),
                obra.getCliente(),
                obra.getDescricao(),
                obra.getCidade(),
                obra.getUf(),
                obra.getRodovia(),
                obra.getStatus(),
                obra.getFonteCriacao(),
                obra.getFonteArquivo(),
                obra.getObservacoes(),
                obra.getCriadoEm(),
                obra.getAtualizadoEm()
        );
    }
}

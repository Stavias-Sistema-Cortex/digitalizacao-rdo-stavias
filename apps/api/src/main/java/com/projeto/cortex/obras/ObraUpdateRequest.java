package com.projeto.cortex.obras;

public record ObraUpdateRequest(
        String codigoContrato,
        String codigoInterno,
        String nome,
        String cliente,
        String descricao,
        String cidade,
        String uf,
        String rodovia,
        String fonteArquivo,
        String observacoes,
        Long baseVersion
) implements ObraCadastroRequest {
}

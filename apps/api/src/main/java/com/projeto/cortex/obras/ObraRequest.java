package com.projeto.cortex.obras;

public record ObraRequest(
        String codigoContrato,
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
        String observacoes
) implements ObraCadastroRequest {
}

interface ObraCadastroRequest {

    String codigoContrato();

    String codigoInterno();

    String nome();

    String cliente();

    String descricao();

    String cidade();

    String uf();

    String rodovia();

    String fonteArquivo();

    String observacoes();
}

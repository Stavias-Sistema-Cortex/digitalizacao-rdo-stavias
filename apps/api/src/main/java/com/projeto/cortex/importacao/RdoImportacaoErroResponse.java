package com.projeto.cortex.importacao;

public record RdoImportacaoErroResponse(
        String id,
        String campo,
        String valorRecebido,
        String valorEsperado,
        String mensagem,
        String sugestao,
        String severidade
) {
}

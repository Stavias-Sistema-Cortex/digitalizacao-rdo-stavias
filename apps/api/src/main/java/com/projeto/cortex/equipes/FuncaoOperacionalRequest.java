package com.projeto.cortex.equipes;

public record FuncaoOperacionalRequest(
        String id,
        String codigo,
        String nome,
        String descricao,
        Boolean ativo,
        Integer ordemExibicao,
        Long baseVersao
) {
}

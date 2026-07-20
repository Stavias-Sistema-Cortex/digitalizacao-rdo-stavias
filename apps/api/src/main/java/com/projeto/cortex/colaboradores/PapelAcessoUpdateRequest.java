package com.projeto.cortex.colaboradores;

public record PapelAcessoUpdateRequest(
        String papelAtual,
        String papelNovo,
        Long baseVersao,
        String motivo,
        Boolean confirmacaoExplicita
) {
}

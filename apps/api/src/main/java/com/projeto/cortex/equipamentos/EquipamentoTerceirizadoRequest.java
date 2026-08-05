package com.projeto.cortex.equipamentos;

public record EquipamentoTerceirizadoRequest(
        String id,
        String prefixo,
        String nome,
        String categoria,
        String fornecedor,
        String documentoFornecedor,
        String observacoes,
        Boolean ativo,
        Long baseVersao
) {
}

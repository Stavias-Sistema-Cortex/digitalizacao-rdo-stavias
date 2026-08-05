package com.projeto.cortex.equipamentos;

import java.time.LocalDateTime;

public record EquipamentoTerceirizadoResponse(
        String id,
        String assetId,
        String prefixo,
        String nome,
        String categoria,
        String fornecedor,
        String documentoFornecedor,
        String observacoes,
        boolean ativo,
        long versaoEntidade,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}

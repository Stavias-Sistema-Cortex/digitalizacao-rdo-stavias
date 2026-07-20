package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record FuncaoOperacionalResponse(
        String id,
        String codigo,
        String nome,
        String descricao,
        boolean ativo,
        int ordemExibicao,
        long versaoEntidade,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}

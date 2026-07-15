package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeMemberResponse(
        String id,
        String equipeId,
        String colaboradorId,
        String colaboradorNome,
        String funcaoOperacionalId,
        String funcaoCodigo,
        String funcaoNome,
        boolean responsavel,
        String status,
        LocalDateTime inicioEm,
        LocalDateTime fimEm,
        String motivoEncerramento,
        long versaoEntidade,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}

package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeMemberRequest(
        String id,
        String colaboradorId,
        String funcaoOperacionalId,
        Boolean responsavel,
        LocalDateTime inicioEm,
        Long baseVersao,
        String motivo,
        Boolean concederAcessoObra
) {
}

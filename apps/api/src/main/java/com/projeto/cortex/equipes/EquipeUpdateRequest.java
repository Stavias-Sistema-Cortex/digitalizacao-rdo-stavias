package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeUpdateRequest(
        String nome,
        String descricao,
        String status,
        LocalDateTime inicioValidadeEm,
        LocalDateTime fimValidadeEm,
        Long baseVersao,
        String motivo
) {
}

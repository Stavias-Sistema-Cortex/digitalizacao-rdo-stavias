package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeCreateRequest(
        String id,
        String obraId,
        String nome,
        String descricao,
        LocalDateTime inicioValidadeEm
) {
}

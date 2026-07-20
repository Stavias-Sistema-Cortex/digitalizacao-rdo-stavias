package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeFilter(
        String obraId,
        String texto,
        String funcaoOperacionalId,
        String status,
        LocalDateTime vigenteEm,
        Integer page,
        Integer size
) {
}

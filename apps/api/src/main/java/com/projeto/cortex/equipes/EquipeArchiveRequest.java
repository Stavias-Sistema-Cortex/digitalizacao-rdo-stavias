package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeArchiveRequest(
        Long baseVersao,
        String motivo,
        LocalDateTime arquivadaEm
) {
}

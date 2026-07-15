package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeWorksiteRequest(
        String id,
        String obraId,
        LocalDateTime inicioEm
) {
}

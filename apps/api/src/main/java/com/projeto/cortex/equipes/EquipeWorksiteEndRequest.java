package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeWorksiteEndRequest(
        Long baseVersao,
        String motivo,
        LocalDateTime encerradoEm
) {
}

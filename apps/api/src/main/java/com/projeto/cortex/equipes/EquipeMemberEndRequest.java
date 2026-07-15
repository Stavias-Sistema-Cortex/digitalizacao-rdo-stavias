package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeMemberEndRequest(
        Long baseVersao,
        String motivo,
        LocalDateTime encerradoEm
) {
}

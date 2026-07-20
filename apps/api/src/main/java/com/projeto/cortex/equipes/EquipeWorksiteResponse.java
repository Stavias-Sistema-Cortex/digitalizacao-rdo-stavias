package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

public record EquipeWorksiteResponse(
        String id,
        String equipeId,
        String obraId,
        String obraNome,
        String status,
        LocalDateTime inicioEm,
        LocalDateTime fimEm,
        String motivoEncerramento,
        long versaoEntidade,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}

package com.projeto.cortex.auth.roles;

import java.time.LocalDateTime;

public record AdminRoleChangeResponse(
        String colaboradorId,
        String nome,
        String papelAnterior,
        String papelAcesso,
        int sessoesRevogadas,
        long commitSeq,
        LocalDateTime alteradoEm
) {
}

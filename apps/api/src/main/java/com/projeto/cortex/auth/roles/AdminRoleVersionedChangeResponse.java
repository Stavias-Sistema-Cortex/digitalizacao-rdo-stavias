package com.projeto.cortex.auth.roles;

import java.time.LocalDateTime;

public record AdminRoleVersionedChangeResponse(
        String colaboradorId,
        String nome,
        String papelAcesso,
        long versaoLinha,
        LocalDateTime atualizadoEm
) {
}

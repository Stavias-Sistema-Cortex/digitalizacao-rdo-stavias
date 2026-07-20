package com.projeto.cortex.colaboradores;

import java.time.LocalDateTime;

public record PapelAcessoUpdateResponse(
        String colaboradorId,
        String colaboradorNome,
        String papelAcesso,
        long versaoLinha,
        LocalDateTime atualizadoEm
) {
}

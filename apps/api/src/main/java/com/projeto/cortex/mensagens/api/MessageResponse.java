package com.projeto.cortex.mensagens.api;

import java.time.LocalDateTime;
import java.util.List;

public record MessageResponse(
        String id,
        String conversaId,
        String autorId,
        String autorNome,
        String corpo,
        String status,
        String clientMutationId,
        LocalDateTime criadaNoClienteEm,
        LocalDateTime criadaEm,
        LocalDateTime editadaEm,
        LocalDateTime deletadaEm,
        long versao,
        List<AttachmentResponse> anexos
) {
}

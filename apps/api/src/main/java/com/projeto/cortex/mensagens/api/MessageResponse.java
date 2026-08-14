package com.projeto.cortex.mensagens.api;

import java.time.Instant;
import java.util.List;

public record MessageResponse(
        String id,
        String conversaId,
        String autorId,
        String autorNome,
        String corpo,
        String status,
        String clientMutationId,
        Instant criadaNoClienteEm,
        Instant criadaEm,
        Instant editadaEm,
        Instant deletadaEm,
        long versao,
        List<AttachmentResponse> anexos
) {
}

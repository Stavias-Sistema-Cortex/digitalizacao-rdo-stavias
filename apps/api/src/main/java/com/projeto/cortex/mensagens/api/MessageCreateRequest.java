package com.projeto.cortex.mensagens.api;

import java.time.LocalDateTime;
import java.util.List;

public record MessageCreateRequest(
        String id,
        String corpo,
        String clientMutationId,
        LocalDateTime criadaNoClienteEm,
        List<AttachmentReferenceRequest> anexos
) {
}

package com.projeto.cortex.mensagens.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.projeto.cortex.common.UtcLocalDateTimeDeserializer;
import java.time.LocalDateTime;
import java.util.List;

public record MessageCreateRequest(
        String id,
        String corpo,
        String clientMutationId,
        @JsonDeserialize(using = UtcLocalDateTimeDeserializer.class)
        LocalDateTime criadaNoClienteEm,
        List<AttachmentReferenceRequest> anexos
) {
}

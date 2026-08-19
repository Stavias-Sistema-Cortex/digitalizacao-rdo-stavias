package com.projeto.cortex.mensagens.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/** Preferencia pessoal autoritativa para uma conversa autorizada. */
public record ConversationPreferenceResponse(
        String conversationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant limpoAte
) {
}

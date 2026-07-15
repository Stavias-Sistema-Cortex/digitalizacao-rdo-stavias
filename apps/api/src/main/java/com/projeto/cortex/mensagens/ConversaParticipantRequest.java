package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

public record ConversaParticipantRequest(
        String id,
        String colaboradorId,
        String papel,
        LocalDateTime entrouEm
) {
}

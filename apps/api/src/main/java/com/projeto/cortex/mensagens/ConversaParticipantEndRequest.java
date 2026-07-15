package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

public record ConversaParticipantEndRequest(
        Long baseVersao,
        String motivo,
        LocalDateTime saiuEm
) {
}

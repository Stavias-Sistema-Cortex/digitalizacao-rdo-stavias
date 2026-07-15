package com.projeto.cortex.mensagens.api;

import java.time.LocalDateTime;

public record ParticipantResponse(
        String colaboradorId,
        String nome,
        String papel,
        String status,
        LocalDateTime adicionadoEm
) {
}

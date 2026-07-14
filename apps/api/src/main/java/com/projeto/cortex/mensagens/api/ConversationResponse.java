package com.projeto.cortex.mensagens.api;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationResponse(
        String id,
        String tipo,
        String titulo,
        String obraId,
        String equipeId,
        String status,
        LocalDateTime criadaEm,
        LocalDateTime atualizadaEm,
        long versao,
        List<ParticipantResponse> participantes
) {
}

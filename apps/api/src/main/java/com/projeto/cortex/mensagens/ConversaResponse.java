package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;
import java.util.List;

public record ConversaResponse(
        String id,
        String tipo,
        String titulo,
        String obraId,
        String obraNome,
        String equipeId,
        String equipeNome,
        String criadoPor,
        String status,
        LocalDateTime ultimaAtividadeEm,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm,
        long versaoEntidade,
        List<ConversaParticipantResponse> participantes
) {
}

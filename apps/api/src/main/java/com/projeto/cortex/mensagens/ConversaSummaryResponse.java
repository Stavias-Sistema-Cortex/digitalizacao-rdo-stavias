package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

public record ConversaSummaryResponse(
        String id,
        String tipo,
        String titulo,
        String obraId,
        String obraNome,
        String equipeId,
        String equipeNome,
        String status,
        LocalDateTime ultimaAtividadeEm,
        String ultimaMensagemId,
        String ultimaMensagemPrevia,
        LocalDateTime ultimaMensagemEm,
        long naoLidas,
        long versaoEntidade
) {
}

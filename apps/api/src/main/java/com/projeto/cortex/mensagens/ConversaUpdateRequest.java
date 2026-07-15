package com.projeto.cortex.mensagens;

public record ConversaUpdateRequest(
        String titulo,
        String status,
        Long baseVersao,
        String motivo
) {
}

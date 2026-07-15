package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

public record MensagemReciboResponse(
        String id,
        String mensagemId,
        String colaboradorId,
        LocalDateTime entregueEm,
        LocalDateTime lidaEm
) {
}

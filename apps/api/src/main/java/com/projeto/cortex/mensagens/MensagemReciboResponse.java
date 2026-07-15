package com.projeto.cortex.mensagens;

import java.time.Instant;

public record MensagemReciboResponse(
        String id,
        String mensagemId,
        String colaboradorId,
        Instant entregueEm,
        Instant lidaEm
) {
}

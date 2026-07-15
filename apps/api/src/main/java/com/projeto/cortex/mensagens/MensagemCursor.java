package com.projeto.cortex.mensagens;

import java.time.Instant;

public record MensagemCursor(
        Instant enviadaClienteEm,
        Instant criadaServidorEm,
        String id
) {
}

package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

public record MensagemCursor(
        LocalDateTime enviadaClienteEm,
        LocalDateTime criadaServidorEm,
        String id
) {
}

package com.projeto.cortex.pdoc;

import java.time.LocalDateTime;

public record PdocErrorResponse(
        LocalDateTime timestamp,
        int status,
        String erro,
        String mensagem,
        String caminho
) {
}

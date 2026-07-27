package com.projeto.cortex.pdor;

import java.time.LocalDateTime;

public record PdorErrorResponse(
        LocalDateTime timestamp,
        int status,
        String erro,
        String mensagem,
        String caminho,
        String codigo,
        String correlationId
) {

    PdorErrorResponse(
            LocalDateTime timestamp,
            int status,
            String erro,
            String mensagem,
            String caminho
    ) {
        this(timestamp, status, erro, mensagem, caminho, null, null);
    }
}

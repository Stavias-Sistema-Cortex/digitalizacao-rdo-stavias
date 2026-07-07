package com.projeto.cortex.pdor;

import java.time.LocalDateTime;

public record PdorErrorResponse(
        LocalDateTime timestamp,
        int status,
        String erro,
        String mensagem,
        String caminho
) {
}

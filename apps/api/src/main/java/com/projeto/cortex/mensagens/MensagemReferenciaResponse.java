package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

public record MensagemReferenciaResponse(
        String id,
        String mensagemId,
        String tipoObjeto,
        String objetoId,
        String obraId,
        LocalDateTime criadoEm
) {
}

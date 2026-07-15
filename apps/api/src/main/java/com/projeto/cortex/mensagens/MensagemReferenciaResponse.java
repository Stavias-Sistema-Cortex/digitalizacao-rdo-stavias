package com.projeto.cortex.mensagens;

import java.time.Instant;

public record MensagemReferenciaResponse(
        String id,
        String mensagemId,
        String tipoObjeto,
        String objetoId,
        String obraId,
        Instant criadoEm
) {
}

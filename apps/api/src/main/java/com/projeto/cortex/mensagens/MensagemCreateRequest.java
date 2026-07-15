package com.projeto.cortex.mensagens;

import java.time.Instant;
import java.util.List;

public record MensagemCreateRequest(
        String id,
        String conversaId,
        String clientMessageId,
        String texto,
        Instant enviadaClienteEm,
        List<MensagemReferenciaRequest> referencias,
        List<MensagemAnexoPreparacaoRequest> anexos
) {
}

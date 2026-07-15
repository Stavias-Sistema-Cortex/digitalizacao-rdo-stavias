package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;
import java.util.List;

public record MensagemCreateRequest(
        String id,
        String conversaId,
        String clientMessageId,
        String texto,
        LocalDateTime enviadaClienteEm,
        List<MensagemReferenciaRequest> referencias,
        List<MensagemAnexoPreparacaoRequest> anexos
) {
}

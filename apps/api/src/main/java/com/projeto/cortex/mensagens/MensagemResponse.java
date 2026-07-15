package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;
import java.util.List;

public record MensagemResponse(
        String id,
        String conversaId,
        String remetenteId,
        String remetenteNome,
        String clientMessageId,
        String texto,
        String estado,
        LocalDateTime enviadaClienteEm,
        LocalDateTime criadaServidorEm,
        LocalDateTime atualizadaEm,
        long versaoEntidade,
        List<MensagemReferenciaResponse> referencias,
        List<MensagemAnexoResponse> anexos,
        List<MensagemReciboResponse> recibos
) {
}

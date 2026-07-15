package com.projeto.cortex.mensagens;

import java.time.Instant;
import java.util.List;

public record MensagemResponse(
        String id,
        String conversaId,
        String remetenteId,
        String remetenteNome,
        String clientMessageId,
        String texto,
        String estado,
        Instant enviadaClienteEm,
        Instant criadaServidorEm,
        Instant atualizadaEm,
        long versaoEntidade,
        List<MensagemReferenciaResponse> referencias,
        List<MensagemAnexoResponse> anexos,
        List<MensagemReciboResponse> recibos
) {
}

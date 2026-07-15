package com.projeto.cortex.mensagens;

import java.time.Instant;

public record MensagemAnexoResponse(
        String id,
        String mensagemId,
        String clientAttachmentId,
        String nomeOriginal,
        String nomeSeguro,
        String mimeType,
        long tamanhoBytes,
        String hashSha256,
        String status,
        String ultimoErro,
        Instant criadoEm,
        Instant atualizadoEm,
        Instant disponivelEm,
        long versaoEntidade
) {
}

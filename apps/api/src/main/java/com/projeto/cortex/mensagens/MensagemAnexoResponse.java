package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

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
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm,
        LocalDateTime disponivelEm,
        long versaoEntidade
) {
}

package com.projeto.cortex.mensagens;

public record MensagemAnexoPreparacaoRequest(
        String id,
        String clientAttachmentId,
        String nomeOriginal,
        String mimeType,
        Long tamanhoBytes,
        String hashSha256
) {
}

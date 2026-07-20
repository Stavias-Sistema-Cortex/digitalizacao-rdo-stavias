package com.projeto.cortex.mensagens.api;

public record AttachmentResponse(
        String id,
        String objetoId,
        String nome,
        String mediaType,
        long tamanhoBytes,
        String sha256,
        int ordem
) {
}

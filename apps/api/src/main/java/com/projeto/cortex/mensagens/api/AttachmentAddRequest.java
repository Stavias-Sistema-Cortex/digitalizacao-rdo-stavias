package com.projeto.cortex.mensagens.api;

public record AttachmentAddRequest(
        String id,
        String objetoId,
        String sha256
) {
}

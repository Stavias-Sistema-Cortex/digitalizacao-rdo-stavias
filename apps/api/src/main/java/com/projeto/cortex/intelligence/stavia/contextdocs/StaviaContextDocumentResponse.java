package com.projeto.cortex.intelligence.stavia.contextdocs;

import java.time.LocalDateTime;

public record StaviaContextDocumentResponse(
        String id,
        String obraId,
        String nomeArquivo,
        String contentType,
        long tamanhoBytes,
        String hashSha256,
        String descricao,
        String statusProcessamento,
        boolean textoDisponivel,
        LocalDateTime criadoEm
) {
}

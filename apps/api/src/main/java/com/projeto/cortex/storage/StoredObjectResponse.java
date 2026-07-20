package com.projeto.cortex.storage;

import java.time.LocalDateTime;

public record StoredObjectResponse(
        String id,
        String obraId,
        String nomeOriginal,
        String mediaType,
        long tamanhoBytes,
        String sha256,
        String status,
        LocalDateTime criadoEm,
        boolean deduplicado
) {

    static StoredObjectResponse from(StoredObjectUploadResult result) {
        StoredObjectRecord object = result.object();
        return new StoredObjectResponse(
                object.id(),
                object.obraId(),
                object.originalName(),
                object.detectedMediaType(),
                object.size(),
                object.sha256(),
                object.status(),
                object.createdAt(),
                !result.created()
        );
    }
}

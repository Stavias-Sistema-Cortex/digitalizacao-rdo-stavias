package com.projeto.cortex.mensagens;

import java.time.Instant;

record MessageAttachmentRecord(
        String id,
        String messageId,
        String uploadedBy,
        String clientAttachmentId,
        String originalFilename,
        String safeFilename,
        String mimeType,
        long sizeBytes,
        String sha256,
        String storageKey,
        String status,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant availableAt,
        long entityVersion
) {

    static MessageAttachmentRecord pending(
            String id,
            String messageId,
            String uploadedBy,
            String clientAttachmentId,
            String originalFilename,
            String safeFilename,
            String mimeType,
            long sizeBytes,
            String sha256,
            String status,
            long entityVersion
    ) {
        return new MessageAttachmentRecord(
                id, messageId, uploadedBy, clientAttachmentId,
                originalFilename, safeFilename, mimeType, sizeBytes, sha256,
                null, status, null, null, null, null, entityVersion
        );
    }

    MensagemAnexoResponse response() {
        return new MensagemAnexoResponse(
                id,
                messageId,
                clientAttachmentId,
                originalFilename,
                safeFilename,
                mimeType,
                sizeBytes,
                sha256,
                status,
                lastError,
                createdAt,
                updatedAt,
                availableAt,
                entityVersion
        );
    }
}

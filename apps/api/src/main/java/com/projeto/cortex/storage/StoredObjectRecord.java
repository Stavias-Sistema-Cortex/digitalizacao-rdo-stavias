package com.projeto.cortex.storage;

import java.time.LocalDateTime;

public record StoredObjectRecord(
        String id,
        String ownerId,
        String obraId,
        String dedupeKey,
        String sha256,
        String storageBackend,
        String storageKey,
        String originalName,
        String declaredMediaType,
        String detectedMediaType,
        long size,
        String status,
        LocalDateTime createdAt
) {

    public StoredObjectRecord available() {
        return new StoredObjectRecord(
                id,
                ownerId,
                obraId,
                dedupeKey,
                sha256,
                storageBackend,
                storageKey,
                originalName,
                declaredMediaType,
                detectedMediaType,
                size,
                "DISPONIVEL",
                createdAt
        );
    }
}

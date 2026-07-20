package com.projeto.cortex.storage;

public record InspectedObjectContent(
        String sha256,
        long size,
        String declaredMediaType,
        String detectedMediaType
) {
}

package com.projeto.cortex.intelligence.stavia.model;

import java.time.Instant;
import java.util.Map;

public record StaviaEvidence(
        String type,
        String id,
        String summary,
        Instant updatedAt,
        boolean validated,
        Map<String, Object> attributes
) {

    public StaviaEvidence {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    "O tipo da evidência deve ser informado."
            );
        }

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "O ID da evidência deve ser informado."
            );
        }

        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException(
                    "O resumo da evidência deve ser informado."
            );
        }

        type = type.trim().toUpperCase();
        id = id.trim();
        summary = summary.trim();

        attributes = attributes == null
                ? Map.of()
                : Map.copyOf(attributes);
    }
}

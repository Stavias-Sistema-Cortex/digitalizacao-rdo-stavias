package com.projeto.cortex.intelligence.stavia.prompt;

import java.time.Instant;
import java.util.Map;

public record StaviaPromptEvidence(
        String sourceKey,
        String type,
        String summary,
        Instant updatedAt,
        boolean validated,
        Map<String, Object> attributes
) {

    public StaviaPromptEvidence {
        requireText(
                sourceKey,
                "A chave da fonte deve ser informada."
        );

        requireText(
                type,
                "O tipo da evidência deve ser informado."
        );

        requireText(
                summary,
                "O resumo da evidência deve ser informado."
        );

        attributes = attributes == null
                ? Map.of()
                : Map.copyOf(attributes);
    }

    private static void requireText(
            String value,
            String message
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

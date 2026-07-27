package com.projeto.cortex.intelligence.stavia.prompt;

import java.util.List;

public record StaviaPrompt(
        String version,
        String systemInstruction,
        String userQuestion,
        String intent,
        List<StaviaPromptEvidence> evidences
) {

    public StaviaPrompt {
        requireText(
                version,
                "A versão do prompt deve ser informada."
        );

        requireText(
                systemInstruction,
                "A instrução de sistema deve ser informada."
        );

        requireText(
                userQuestion,
                "A pergunta do usuário deve ser informada."
        );

        requireText(
                intent,
                "A intenção da pergunta deve ser informada."
        );

        evidences = evidences == null
                ? List.of()
                : List.copyOf(evidences);
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

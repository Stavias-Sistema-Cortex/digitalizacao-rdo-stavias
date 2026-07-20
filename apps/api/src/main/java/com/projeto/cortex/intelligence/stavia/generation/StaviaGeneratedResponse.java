package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;

import java.util.List;

public record StaviaGeneratedResponse(
        String text,
        StaviaAnswerType answerType,
        List<String> sourceKeys
) {

    public StaviaGeneratedResponse {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "O texto gerado pela Stav.IA não pode estar vazio."
            );
        }

        if (answerType == null) {
            throw new IllegalArgumentException(
                    "O tipo da resposta gerada deve ser informado."
            );
        }

        text = text.trim();

        sourceKeys = sourceKeys == null
                ? List.of()
                : List.copyOf(sourceKeys);
    }
}

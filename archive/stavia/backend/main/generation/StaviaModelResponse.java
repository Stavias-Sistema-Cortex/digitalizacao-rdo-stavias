package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;

import java.util.List;

public record StaviaModelResponse(
        String text,
        StaviaAnswerType answerType,
        List<String> sourceKeys
) {

    public StaviaModelResponse {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "O modelo deve devolver um texto."
            );
        }

        if (answerType == null) {
            throw new IllegalArgumentException(
                    "O modelo deve devolver o tipo da resposta."
            );
        }

        text = text.trim();

        sourceKeys = sourceKeys == null
                ? List.of()
                : List.copyOf(sourceKeys);
    }
}

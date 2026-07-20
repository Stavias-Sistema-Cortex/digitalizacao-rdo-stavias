package com.projeto.cortex.intelligence.stavia.model;

import java.util.List;

public record StaviaAnswer(
        String answer,
        StaviaConfidence confidence,
        StaviaAnswerType answerType,
        List<StaviaEvidence> sources,
        boolean insufficientData,
        List<String> warnings,
        StaviaExecutionMetadata metadata
) {

    public StaviaAnswer {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException(
                    "A resposta da Stav.IA não pode estar vazia."
            );
        }

        if (confidence == null) {
            throw new IllegalArgumentException(
                    "A confiança da resposta deve ser informada."
            );
        }

        if (answerType == null) {
            throw new IllegalArgumentException(
                    "O tipo da resposta deve ser informado."
            );
        }

        answer = answer.trim();

        sources = sources == null
                ? List.of()
                : List.copyOf(sources);

        warnings = warnings == null
                ? List.of()
                : List.copyOf(warnings);
    }

    /*
     * Construtor temporário para compatibilidade com os testes
     * e componentes que ainda não produzem metadados.
     */
    public StaviaAnswer(
            String answer,
            StaviaConfidence confidence,
            StaviaAnswerType answerType,
            List<StaviaEvidence> sources,
            boolean insufficientData,
            List<String> warnings
    ) {
        this(
                answer,
                confidence,
                answerType,
                sources,
                insufficientData,
                warnings,
                null
        );
    }
}

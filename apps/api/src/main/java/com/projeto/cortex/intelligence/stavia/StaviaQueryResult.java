package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;

import java.util.List;
import java.util.Map;

public record StaviaQueryResult(
        StaviaAnswer answer,
        StaviaIntent intent,
        Map<String, String> consultedKnowledgeSources,
        List<String> knowledgeWarnings
) {

    public StaviaQueryResult {
        if (answer == null) {
            throw new IllegalArgumentException(
                    "A resposta da Stav.IA deve ser informada."
            );
        }

        if (intent == null) {
            throw new IllegalArgumentException(
                    "A intenção da consulta deve ser informada."
            );
        }

        consultedKnowledgeSources =
                consultedKnowledgeSources == null
                        ? Map.of()
                        : Map.copyOf(
                                consultedKnowledgeSources
                        );

        knowledgeWarnings =
                knowledgeWarnings == null
                        ? List.of()
                        : List.copyOf(
                                knowledgeWarnings
                        );
    }
}

package com.projeto.cortex.intelligence.stavia.intent;

/**
 * Result of intent classification: the chosen intent plus a confidence in
 * {@code [0,1]} expressing how unambiguous the choice was (the share of matched
 * signals attributable to the winning intent). {@code DESCONHECIDA} carries
 * confidence {@code 0}.
 */
public record StaviaClassification(
        StaviaIntent intent,
        double confidence
) {

    public StaviaClassification {
        if (intent == null) {
            throw new IllegalArgumentException(
                    "A intenção classificada deve ser informada."
            );
        }

        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException(
                    "A confiança deve estar entre 0 e 1."
            );
        }
    }
}

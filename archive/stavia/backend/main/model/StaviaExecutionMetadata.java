package com.projeto.cortex.intelligence.stavia.model;

import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;

import java.time.Instant;

public record StaviaExecutionMetadata(
        String engineVersion,
        String intentClassifierVersion,
        String evidenceSelectionVersion,
        String groundingPolicyVersion,
        String qualityPolicyVersion,
        String contradictionPolicyVersion,
        Instant processedAt
) {

    public StaviaExecutionMetadata {
        requireText(
                engineVersion,
                "A versão do motor deve ser informada."
        );

        requireText(
                intentClassifierVersion,
                "A versão do classificador deve ser informada."
        );

        requireText(
                evidenceSelectionVersion,
                "A versão da seleção de evidências deve ser informada."
        );

        requireText(
                groundingPolicyVersion,
                "A versão da política de grounding deve ser informada."
        );

        requireText(
                qualityPolicyVersion,
                "A versão da política de qualidade deve ser informada."
        );

        requireText(
                contradictionPolicyVersion,
                "A versão da política de contradição deve ser informada."
        );

        if (processedAt == null) {
            throw new IllegalArgumentException(
                    "A data de processamento deve ser informada."
            );
        }
    }

    public static StaviaExecutionMetadata current(
            Instant processedAt
    ) {
        return new StaviaExecutionMetadata(
                StaviaVersions.ENGINE,
                StaviaVersions.INTENT_CLASSIFIER,
                StaviaVersions.EVIDENCE_SELECTION,
                StaviaVersions.GROUNDING_POLICY,
                StaviaVersions.QUALITY_POLICY,
                StaviaVersions.CONTRADICTION_POLICY,
                processedAt
        );
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

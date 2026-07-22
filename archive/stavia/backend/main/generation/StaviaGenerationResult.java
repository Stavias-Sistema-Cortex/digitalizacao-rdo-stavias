package com.projeto.cortex.intelligence.stavia.generation;

public record StaviaGenerationResult(
        boolean successful,
        StaviaGeneratedResponse response,
        String internalErrorCode
) {

    public StaviaGenerationResult {
        if (successful && response == null) {
            throw new IllegalArgumentException(
                    "Uma geração bem-sucedida deve possuir resposta."
            );
        }

        if (!successful && response != null) {
            throw new IllegalArgumentException(
                    "Uma geração com falha não deve possuir resposta."
            );
        }

        if (
                !successful
                && (
                        internalErrorCode == null
                        || internalErrorCode.isBlank()
                )
        ) {
            throw new IllegalArgumentException(
                    "Uma falha de geração deve possuir código interno."
            );
        }
    }

    public static StaviaGenerationResult success(
            StaviaGeneratedResponse response
    ) {
        return new StaviaGenerationResult(
                true,
                response,
                null
        );
    }

    public static StaviaGenerationResult failure(
            String internalErrorCode
    ) {
        return new StaviaGenerationResult(
                false,
                null,
                internalErrorCode
        );
    }
}

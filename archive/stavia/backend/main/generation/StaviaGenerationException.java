package com.projeto.cortex.intelligence.stavia.generation;

public class StaviaGenerationException extends RuntimeException {

    public StaviaGenerationException(String message) {
        super(message);
    }

    public StaviaGenerationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}

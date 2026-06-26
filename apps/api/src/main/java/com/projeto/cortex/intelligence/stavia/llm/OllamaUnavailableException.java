package com.projeto.cortex.intelligence.stavia.llm;

public class OllamaUnavailableException extends RuntimeException {
    public OllamaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
    public OllamaUnavailableException(String message) {
        super(message);
    }
}

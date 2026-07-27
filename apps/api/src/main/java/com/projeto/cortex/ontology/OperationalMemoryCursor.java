package com.projeto.cortex.ontology;

public record OperationalMemoryCursor(String token) {
    public OperationalMemoryCursor {
        if (token == null || token.isBlank() || token.length() > 2_048) {
            throw new IllegalArgumentException("Operational Memory cursor is invalid.");
        }
        token = token.trim();
    }
}

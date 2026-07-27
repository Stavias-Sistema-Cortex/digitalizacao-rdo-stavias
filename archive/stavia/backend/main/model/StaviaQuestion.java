package com.projeto.cortex.intelligence.stavia.model;

public record StaviaQuestion(
        String text,
        String userId,
        String obraId
) {

    public StaviaQuestion {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "A pergunta da Stav.IA não pode estar vazia."
            );
        }

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(
                    "O usuário da consulta deve ser informado."
            );
        }

        text = text.trim();
        userId = userId.trim();
        obraId = normalizeOptional(obraId);
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}

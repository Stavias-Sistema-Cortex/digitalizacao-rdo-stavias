package com.projeto.cortex.intelligence.stavia.api;

public record StaviaConsultaRequest(
        String pergunta,
        String usuarioId,
        String obraId
) {

    public StaviaConsultaRequest {
        if (pergunta == null || pergunta.isBlank()) {
            throw new IllegalArgumentException(
                    "A pergunta deve ser informada."
            );
        }

        if (usuarioId == null || usuarioId.isBlank()) {
            throw new IllegalArgumentException(
                    "O usuário deve ser informado."
            );
        }

        pergunta = pergunta.trim();
        usuarioId = usuarioId.trim();

        obraId = obraId == null || obraId.isBlank()
                ? null
                : obraId.trim();
    }
}

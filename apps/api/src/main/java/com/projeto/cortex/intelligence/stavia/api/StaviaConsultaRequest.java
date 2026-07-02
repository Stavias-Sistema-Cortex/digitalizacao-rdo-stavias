package com.projeto.cortex.intelligence.stavia.api;

public record StaviaConsultaRequest(
        String pergunta,
        String usuarioId,
        String obraId,
        String rdoId,
        String contextoSelecionado,
        String ultimoObraId,
        String ultimoRdoId
) {

    public StaviaConsultaRequest(
            String pergunta,
            String usuarioId,
            String obraId
    ) {
        this(pergunta, usuarioId, obraId, null, null, null, null);
    }

    public StaviaConsultaRequest {
        if (pergunta == null || pergunta.isBlank()) {
            throw new IllegalArgumentException(
                    "A pergunta deve ser informada."
            );
        }

        pergunta = pergunta.trim();
        usuarioId = normalizeOptional(usuarioId);

        obraId = obraId == null || obraId.isBlank()
                ? null
                : obraId.trim();
        rdoId = normalizeOptional(rdoId);
        contextoSelecionado = normalizeOptional(contextoSelecionado);
        ultimoObraId = normalizeOptional(ultimoObraId);
        ultimoRdoId = normalizeOptional(ultimoRdoId);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}

package com.projeto.cortex.obras;

/**
 * Corpo para vincular um colaborador a uma obra. {@code papelNaObra} é opcional
 * (assume {@code OPERACIONAL} quando ausente).
 */
public record VinculoColaboradorObraRequest(
        String colaboradorId,
        String papelNaObra
) {
}

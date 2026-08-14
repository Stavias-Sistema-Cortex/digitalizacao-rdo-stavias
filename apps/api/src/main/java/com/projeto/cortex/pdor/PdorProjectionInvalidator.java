package com.projeto.cortex.pdor;

/**
 * Invalida a projeção corrente quando a fonte operacional muda.
 *
 * <p>A invalidação faz parte da mesma transação da mutação de origem. Assim,
 * uma exclusão, um cancelamento ou um arquivamento nunca deixa uma receita
 * antiga visível enquanto o recálculo posterior é executado.</p>
 */
@FunctionalInterface
public interface PdorProjectionInvalidator {

    PdorProjectionInvalidator NOOP = obraId -> { };

    void invalidateCurrent(String obraId);
}

package com.projeto.cortex.obras;

import java.time.LocalDateTime;

/**
 * Vínculo explícito entre um colaborador e uma obra (a "associação" de
 * autorização). Um vínculo {@code ATIVO} concede acesso Beta à obra.
 */
public record VinculoColaboradorObraResponse(
        String id,
        String obraId,
        String colaboradorId,
        String colaboradorNome,
        String status,
        String papelNaObra,
        LocalDateTime atribuidoEm,
        String atribuidoPor,
        LocalDateTime revogadoEm,
        String revogadoPor
) {
}

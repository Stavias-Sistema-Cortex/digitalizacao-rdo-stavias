package com.projeto.cortex.rdos;

public record RdoDeletionResponse(
        String rdoId,
        String obraId,
        String numeroRdo,
        int anexosRemovidos
) {
}

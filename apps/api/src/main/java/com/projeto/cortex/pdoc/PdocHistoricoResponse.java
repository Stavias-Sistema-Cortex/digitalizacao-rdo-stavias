package com.projeto.cortex.pdoc;

import java.util.List;

public record PdocHistoricoResponse(
        List<PdocResultadoResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}

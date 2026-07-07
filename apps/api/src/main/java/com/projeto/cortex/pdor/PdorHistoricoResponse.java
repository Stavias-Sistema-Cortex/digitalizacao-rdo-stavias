package com.projeto.cortex.pdor;

import java.util.List;

public record PdorHistoricoResponse(
        List<PdorResultadoResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}

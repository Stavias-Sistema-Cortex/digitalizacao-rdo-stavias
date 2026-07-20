package com.projeto.cortex.equipes;

import java.util.List;

public record EquipePageResponse(
        List<EquipeResponse> items,
        long totalElements,
        int page,
        int size,
        int totalPages
) {
    public EquipePageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static EquipePageResponse empty(int page, int size) {
        return new EquipePageResponse(List.of(), 0, page, size, 0);
    }
}

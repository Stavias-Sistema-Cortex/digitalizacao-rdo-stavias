package com.projeto.cortex.intelligence.stavia.context;

import java.util.List;

public record StaviaContextResolution(
        String obraId,
        String label,
        List<String> ambiguousOptions
) {

    public static StaviaContextResolution resolved(
            String obraId,
            String label
    ) {
        return new StaviaContextResolution(
                obraId,
                label,
                List.of()
        );
    }

    public static StaviaContextResolution ambiguous(
            List<String> options
    ) {
        return new StaviaContextResolution(
                null,
                null,
                options == null ? List.of() : List.copyOf(options)
        );
    }

    public static StaviaContextResolution notFound() {
        return new StaviaContextResolution(null, null, List.of());
    }

    public boolean found() {
        return obraId != null && !obraId.isBlank();
    }

    public boolean ambiguous() {
        return !found() && !ambiguousOptions.isEmpty();
    }
}

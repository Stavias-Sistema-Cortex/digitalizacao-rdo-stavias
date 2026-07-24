package com.projeto.cortex.intelligence.stavia.planning;

import java.util.List;

public record ResolvedEntity(
        String type,
        String id,
        String resolvedBy,
        String value,
        boolean ambiguous,
        List<String> alternatives
) {

    public ResolvedEntity {
        type = normalize(type);
        id = normalize(id);
        resolvedBy = normalize(resolvedBy);
        value = normalize(value);
        alternatives = alternatives == null
                ? List.of()
                : List.copyOf(alternatives);
    }

    public static ResolvedEntity worksiteById(String id) {
        return new ResolvedEntity(
                "OBRA",
                id,
                "ID",
                id,
                false,
                List.of()
        );
    }

    public static ResolvedEntity collaboratorByName(String name) {
        return new ResolvedEntity("COLABORADOR", null, "NOME", name, false, List.of());
    }

    public static ResolvedEntity roleByLabel(String label) {
        return new ResolvedEntity("ROLE", null, "FUNCAO", label, false, List.of());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}

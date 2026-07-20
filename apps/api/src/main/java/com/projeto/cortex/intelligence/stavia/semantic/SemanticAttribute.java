package com.projeto.cortex.intelligence.stavia.semantic;

import java.util.List;

public record SemanticAttribute(
        String objectType,
        String name,
        List<String> aliases,
        String dataType,
        String unit,
        String source,
        boolean visible,
        boolean sensitive,
        String permission
) {

    public SemanticAttribute {
        objectType = normalize(objectType);
        name = normalize(name);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        dataType = normalize(dataType);
        unit = normalize(unit);
        source = normalize(source);
        permission = normalize(permission);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}

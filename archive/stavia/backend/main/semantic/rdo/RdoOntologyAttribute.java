package com.projeto.cortex.intelligence.stavia.semantic.rdo;

import java.util.List;

public record RdoOntologyAttribute(
        String name,
        String column,
        String label,
        String dataType,
        String unit,
        String unitColumn,
        List<String> aliases,
        boolean aggregable,
        boolean identity
) {

    public RdoOntologyAttribute {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "Atributo da ontologia RDO sem nome."
            );
        }

        if (column == null || column.isBlank()) {
            throw new IllegalStateException(
                    "Atributo da ontologia RDO sem coluna: " + name
            );
        }

        if (label == null || label.isBlank()) {
            throw new IllegalStateException(
                    "Atributo da ontologia RDO sem rótulo: " + name
            );
        }

        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}

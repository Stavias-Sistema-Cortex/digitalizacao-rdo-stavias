package com.projeto.cortex.intelligence.stavia.semantic.rdo;

import java.util.List;
import java.util.Optional;

public record RdoOntologyEntity(
        String name,
        String table,
        String evidenceType,
        String source,
        boolean header,
        String snapshotCollection,
        String countAttribute,
        String filter,
        List<String> aliases,
        List<RdoOntologyAttribute> attributes
) {

    public RdoOntologyEntity {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "Entidade da ontologia RDO sem nome."
            );
        }

        if (table == null || table.isBlank()) {
            throw new IllegalStateException(
                    "Entidade da ontologia RDO sem tabela: " + name
            );
        }

        if (evidenceType == null || evidenceType.isBlank()) {
            throw new IllegalStateException(
                    "Entidade da ontologia RDO sem tipo de evidência: " + name
            );
        }

        if (source == null || source.isBlank()) {
            throw new IllegalStateException(
                    "Entidade da ontologia RDO sem fonte: " + name
            );
        }

        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        attributes = attributes == null
                ? List.of()
                : List.copyOf(attributes);
    }

    public Optional<RdoOntologyAttribute> attributeByName(String value) {
        return attributes.stream()
                .filter(attribute -> attribute.name().equals(value))
                .findFirst();
    }

    public List<RdoOntologyAttribute> identityAttributes() {
        return attributes.stream()
                .filter(RdoOntologyAttribute::identity)
                .toList();
    }
}

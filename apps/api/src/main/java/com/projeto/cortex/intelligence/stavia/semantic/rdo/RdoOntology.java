package com.projeto.cortex.intelligence.stavia.semantic.rdo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Declarative read model of the RDO tables: entities, columns, labels,
 * units and PT-BR aliases. Loaded once from a versioned classpath resource
 * shared with the offline web engine, so both answer pipelines agree on
 * what exists and how users refer to it.
 */
public final class RdoOntology {

    private static final String RESOURCE = "/stavia/rdo-ontology.json";

    private final String version;
    private final List<RdoOntologyEntity> entities;
    private final JsonNode raw;

    private RdoOntology(
            String version,
            List<RdoOntologyEntity> entities,
            JsonNode raw
    ) {
        this.version = version;
        this.entities = List.copyOf(entities);
        this.raw = raw;
    }

    public static RdoOntology load() {
        try (InputStream stream =
                     RdoOntology.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Recurso da ontologia RDO não encontrado: "
                                + RESOURCE
                );
            }

            return parse(
                    new String(
                            stream.readAllBytes(),
                            StandardCharsets.UTF_8
                    )
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Falha ao carregar a ontologia RDO.",
                    exception
            );
        }
    }

    public static RdoOntology parse(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            String version = root.path("version").asText(null);

            if (version == null || version.isBlank()) {
                throw new IllegalStateException(
                        "Ontologia RDO sem versão."
                );
            }

            List<RdoOntologyEntity> entities = new ArrayList<>();
            for (JsonNode entity : root.path("entities")) {
                entities.add(parseEntity(entity));
            }

            if (entities.isEmpty()) {
                throw new IllegalStateException(
                        "Ontologia RDO sem entidades."
                );
            }

            return new RdoOntology(version, entities, root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(
                    "JSON da ontologia RDO inválido.",
                    exception
            );
        }
    }

    private static RdoOntologyEntity parseEntity(JsonNode node) {
        List<RdoOntologyAttribute> attributes = new ArrayList<>();

        for (JsonNode attribute : node.path("attributes")) {
            attributes.add(
                    new RdoOntologyAttribute(
                            attribute.path("name").asText(null),
                            attribute.path("column").asText(null),
                            attribute.path("label").asText(null),
                            attribute.path("dataType").asText(null),
                            textOrNull(attribute, "unit"),
                            textOrNull(attribute, "unitColumn"),
                            textList(attribute, "aliases"),
                            attribute.path("aggregable").asBoolean(false),
                            attribute.path("identity").asBoolean(false)
                    )
            );
        }

        return new RdoOntologyEntity(
                node.path("name").asText(null),
                node.path("table").asText(null),
                node.path("evidenceType").asText(null),
                node.path("source").asText(null),
                node.path("header").asBoolean(false),
                textOrNull(node, "snapshotCollection"),
                textOrNull(node, "countAttribute"),
                textOrNull(node, "filter"),
                textOrNull(node, "scope"),
                textList(node, "aliases"),
                attributes
        );
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);

        return value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : null;
    }

    private static List<String> textList(JsonNode node, String field) {
        List<String> values = new ArrayList<>();

        for (JsonNode value : node.path(field)) {
            values.add(value.asText());
        }

        return values;
    }

    public String version() {
        return version;
    }

    public List<RdoOntologyEntity> entities() {
        return entities;
    }

    public JsonNode raw() {
        return raw;
    }

    public Optional<RdoOntologyEntity> entityByName(String value) {
        return entities.stream()
                .filter(entity -> entity.name().equals(value))
                .findFirst();
    }
}

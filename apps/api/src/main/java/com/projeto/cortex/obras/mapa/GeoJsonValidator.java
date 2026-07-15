package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

final class GeoJsonValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "Point", "MultiPoint", "LineString", "MultiLineString",
            "Polygon", "MultiPolygon"
    );

    private GeoJsonValidator() {
    }

    static String validate(JsonNode geometry) {
        if (geometry == null || !geometry.isObject()) {
            throw badRequest("Geometria GeoJSON obrigatória.");
        }

        String type = geometry.path("type").asText("");
        if (!SUPPORTED_TYPES.contains(type)) {
            throw badRequest("Geometria GeoJSON não suportada: " + type + ".");
        }

        JsonNode coordinates = geometry.get("coordinates");
        if (coordinates == null || !coordinates.isArray() || coordinates.isEmpty()) {
            throw badRequest("GeoJSON deve possuir coordenadas não vazias.");
        }

        validateShape(type, coordinates);
        validateCoordinatePairs(coordinates);
        if (type.equals("Polygon")) {
            validatePolygon(coordinates);
        } else if (type.equals("MultiPolygon")) {
            for (JsonNode polygon : coordinates) {
                validatePolygon(polygon);
            }
        }

        return type.toUpperCase(Locale.ROOT);
    }

    private static void validateShape(String type, JsonNode coordinates) {
        switch (type) {
            case "Point" -> requirePosition(coordinates);
            case "MultiPoint", "LineString" -> requirePositions(coordinates, 1);
            case "MultiLineString", "Polygon" -> requireNestedPositions(coordinates);
            case "MultiPolygon" -> {
                for (JsonNode polygon : coordinates) {
                    requireNestedPositions(polygon);
                }
            }
            default -> throw badRequest("Geometria GeoJSON não suportada.");
        }
    }

    private static void requirePosition(JsonNode node) {
        if (!node.isArray() || node.size() < 2
                || !node.get(0).isNumber() || !node.get(1).isNumber()) {
            throw badRequest("Coordenadas GeoJSON devem ser numéricas [longitude, latitude].");
        }
    }

    private static void requirePositions(JsonNode node, int minimum) {
        if (!node.isArray() || node.size() < minimum) {
            throw badRequest("GeoJSON possui estrutura de coordenadas inválida.");
        }
        for (JsonNode position : node) {
            requirePosition(position);
        }
    }

    private static void requireNestedPositions(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw badRequest("GeoJSON possui estrutura de coordenadas inválida.");
        }
        for (JsonNode positions : node) {
            requirePositions(positions, 1);
        }
    }

    private static void validateCoordinatePairs(JsonNode node) {
        if (node.isArray() && node.size() >= 2
                && node.get(0).isNumber() && node.get(1).isNumber()) {
            double longitude = node.get(0).asDouble();
            double latitude = node.get(1).asDouble();
            if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                throw badRequest("Coordenada de longitude fora do intervalo -180 a 180.");
            }
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
                throw badRequest("Coordenada de latitude fora do intervalo -90 a 90.");
            }
            return;
        }

        if (!node.isArray()) {
            throw badRequest("Coordenadas GeoJSON devem ser numéricas.");
        }
        for (JsonNode child : node) {
            validateCoordinatePairs(child);
        }
    }

    private static void validatePolygon(JsonNode polygon) {
        for (JsonNode ring : polygon) {
            if (!ring.isArray() || ring.size() < 4) {
                throw badRequest("Anel de polígono deve possuir ao menos quatro posições.");
            }
            JsonNode first = ring.get(0);
            JsonNode last = ring.get(ring.size() - 1);
            if (!first.equals(last)) {
                throw badRequest("Anel de polígono deve ser fechado.");
            }
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

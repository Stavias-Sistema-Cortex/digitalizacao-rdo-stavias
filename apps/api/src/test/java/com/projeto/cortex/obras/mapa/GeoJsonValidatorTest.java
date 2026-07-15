package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoJsonValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsRealPointLineAndPolygonGeometries() throws Exception {
        assertThat(GeoJsonValidator.validate(geometry("""
                {"type":"Point","coordinates":[-54.6464,-20.4428]}
                """))).isEqualTo("POINT");
        assertThat(GeoJsonValidator.validate(geometry("""
                {"type":"LineString","coordinates":[[-54.65,-20.44],[-54.63,-20.43]]}
                """))).isEqualTo("LINESTRING");
        assertThat(GeoJsonValidator.validate(geometry("""
                {"type":"Polygon","coordinates":[[[-54.65,-20.44],[-54.63,-20.44],[-54.63,-20.42],[-54.65,-20.44]]]}
                """))).isEqualTo("POLYGON");
    }

    @Test
    void rejectsUnsupportedOrOutOfRangeGeometry() throws Exception {
        assertThatThrownBy(() -> GeoJsonValidator.validate(geometry("""
                {"type":"GeometryCollection","geometries":[]}
                """)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não suportada");

        assertThatThrownBy(() -> GeoJsonValidator.validate(geometry("""
                {"type":"Point","coordinates":[-181,-20]}
                """)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    void rejectsOpenPolygonRingsAndNonNumericCoordinates() throws Exception {
        assertThatThrownBy(() -> GeoJsonValidator.validate(geometry("""
                {"type":"Polygon","coordinates":[[[-54.65,-20.44],[-54.63,-20.44],[-54.63,-20.42],[-54.64,-20.43]]]}
                """)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fechado");

        assertThatThrownBy(() -> GeoJsonValidator.validate(geometry("""
                {"type":"Point","coordinates":["-54.65",-20.44]}
                """)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("numéricas");
    }

    private JsonNode geometry(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}

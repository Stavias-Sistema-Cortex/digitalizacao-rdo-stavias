package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Map;

public record ObraGeometriaRequest(
        String categoria,
        String objetoTipo,
        String objetoId,
        JsonNode geometry,
        Map<String, Object> properties,
        String fonte,
        LocalDateTime validoDesde,
        Long baseVersao,
        String motivo
) {
}

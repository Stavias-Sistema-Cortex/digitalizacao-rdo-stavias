package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Map;

public record ObraGeometriaResponse(
        String id,
        String categoria,
        String objetoTipo,
        String objetoId,
        JsonNode geometry,
        Map<String, Object> properties,
        String fonte,
        String status,
        LocalDateTime validoDesde,
        LocalDateTime validoAte,
        String motivoEncerramento,
        long versao,
        String criadoPor,
        String atualizadoPor,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}

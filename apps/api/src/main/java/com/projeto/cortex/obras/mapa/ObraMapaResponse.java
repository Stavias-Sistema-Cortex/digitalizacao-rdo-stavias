package com.projeto.cortex.obras.mapa;

import java.math.BigDecimal;
import java.util.List;

public record ObraMapaResponse(
        ObraLocalizacaoResponse obra,
        List<ObraGeometriaResponse> features
) {
    public record ObraLocalizacaoResponse(
            String id,
            String nome,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}

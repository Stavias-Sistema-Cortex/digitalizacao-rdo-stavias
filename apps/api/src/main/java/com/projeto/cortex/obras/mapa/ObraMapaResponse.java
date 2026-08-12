package com.projeto.cortex.obras.mapa;

import java.math.BigDecimal;
import java.util.List;

/**
 * O mapa da obra como o servidor o enxerga.
 *
 * <p>{@code rdosComLinhaSilenciada} não descreve nada desenhado: descreve o
 * que foi deliberadamente calado. O aparelho precisa saber disso porque ele
 * também deriva linhas — as do RDO que ainda não subiu — e sem esta lista ele
 * redesenharia justamente a linha que alguém acabou de tirar do mapa.
 */
public record ObraMapaResponse(
        ObraLocalizacaoResponse obra,
        List<ObraGeometriaResponse> features,
        List<String> rdosComLinhaSilenciada
) {
    public record ObraLocalizacaoResponse(
            String id,
            String nome,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}

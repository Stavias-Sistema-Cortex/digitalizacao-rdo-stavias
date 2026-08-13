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
        List<String> rdosComLinhaSilenciada,
        /**
         * O que o RDO apontou por quilômetro e não virou linha — nulo quando
         * tudo virou. Sem isto o mapa ficava vazio sem dizer por quê, e quem
         * apontou o quilômetro no RDO não tinha como saber se o dado estava
         * errado ou se faltava cadastrar a régua da obra.
         */
        TrechoApoiadoNoEixo.ApontamentosSemLinha apontamentosSemLinha
) {
    public record ObraLocalizacaoResponse(
            String id,
            String nome,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}

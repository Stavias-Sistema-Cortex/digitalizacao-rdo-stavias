package com.projeto.cortex.obras.rateio;

import java.time.LocalDate;
import java.util.List;

/**
 * Os RDOs de um período, com a mão de obra que cada um apontou.
 *
 * <p>O servidor entrega fatos, não o rateio pronto. A conta — quem esteve em
 * que obra, quanto do dia coube a cada uma, que fatia do mês sobrou para cada
 * frente — é feita num único lugar do produto, o núcleo que roda no aparelho.
 * Se o servidor calculasse também, seriam duas implementações da mesma regra
 * envelhecendo lado a lado, e a divergência entre elas apareceria como um
 * percentual que muda ao entrar e sair da rede, sem que ninguém soubesse qual
 * dos dois estava certo.
 *
 * <p>O que este endpoint acrescenta ao que o aparelho já sabe é <b>alcance</b>:
 * o celular tem os RDOs que a sincronização trouxe, e o banco tem todos.
 */
public record RateioMaoDeObraResponse(
        LocalDate inicio,
        LocalDate fim,
        List<RdoDoRateio> rdos,
        /**
         * Falso quando a consulta encostou no teto e há período fora da
         * resposta. A tela avisa em vez de mostrar um retrato menor calado.
         */
        boolean completo
) {

    /** Um RDO e quem trabalhou nele. */
    public record RdoDoRateio(
            String id,
            String obraId,
            LocalDate dataRdo,
            String numeroRdo,
            /** Texto livre do RDO; costuma vir vazio no que é feito em campo. */
            String encarregadoObra,
            /** Quem assina o documento, e a última porta para achar a frente. */
            String apontadorRdo,
            List<MaoDeObraDoRateio> maoObra
    ) {}

    /**
     * Uma pessoa apontada.
     *
     * <p>Só o necessário para o rateio: quem é, como se chamava naquele dia e
     * que função exercia. Nada de CPF, hora ou observação — o que não serve à
     * conta não precisa viajar até o aparelho.
     */
    public record MaoDeObraDoRateio(
            String colaboradorId,
            String nomeColaborador,
            String cargo
    ) {}
}

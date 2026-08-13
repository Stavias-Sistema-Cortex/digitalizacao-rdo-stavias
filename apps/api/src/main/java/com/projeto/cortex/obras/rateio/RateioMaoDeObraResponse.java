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
            /**
             * O nome da obra, para a tela não depender de já conhecê-la.
             *
             * <p>O aparelho tem a lista de obras que baixou, e ela pode estar
             * atrás do que o rateio mostra: obra criada hoje, RDO lançado
             * hoje, e o celular de quem não abriu a lista ainda. Sem o nome
             * aqui, essa obra apareceria como um identificador cru — ou pior,
             * sumiria do recorte de obras em execução, e duas pessoas veriam
             * números diferentes do mesmo mês.
             */
            String obraNome,
            LocalDate dataRdo,
            String numeroRdo,
            /** Texto livre do RDO; costuma vir vazio no que é feito em campo. */
            String encarregadoObra,
            /** Quem assina o documento, e a última porta para achar a frente. */
            String apontadorRdo,
            /**
             * O identificador de quem assina, quando o apontador foi escolhido
             * da lista da obra em vez de digitado.
             *
             * <p>Viaja porque a assinatura conta como presença: com o
             * identificador, a mesma pessoa escrita de dois jeitos em dois
             * RDOs continua sendo uma pessoa só no rateio.
             */
            String apontadorColaboradorId,
            /**
             * Quem preencheu o documento — nasce do nome da sessão de quem o
             * criou e é editável.
             *
             * <p>É a assinatura mais comum do RDO feito no aplicativo: o campo
             * de encarregado costuma vir vazio, e sem este nome um dia inteiro
             * de trabalho entrava no rateio como dia de ninguém.
             */
            String preenchidoPor,
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

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
             * O ofício do apontador no cadastro (Academy), quando o RDO o
             * escolheu da lista. É o que faz o rateio mostrar "Apontador de
             * obra" em vez de um rótulo genérico — a função é da pessoa, não
             * do papel que ela exerceu no documento.
             */
            String apontadorFuncao,
            /**
             * Quem preencheu o documento — nasce do nome da sessão de quem o
             * criou e é editável.
             *
             * <p>É a assinatura mais comum do RDO feito no aplicativo: o campo
             * de encarregado costuma vir vazio, e sem este nome um dia inteiro
             * de trabalho entrava no rateio como dia de ninguém.
             */
            String preenchidoPor,
            /**
             * O ofício de quem preencheu, achado no cadastro pelo nome.
             *
             * <p>Este campo é texto livre — nasce do nome da sessão e é
             * editável —, então não há identificador para ligar à pessoa. O que
             * há é o nome, e o nome basta quando ele aponta para uma pessoa só:
             * o cadastro é consultado por nome comparável (sem acento e sem
             * caixa) e o ofício só viaja quando não há dúvida sobre de quem ele
             * é. Havendo dois cadastros com o mesmo nome e ofícios diferentes,
             * nada vem — e a tela mostra o rótulo do papel, que é verdadeiro,
             * em vez de arriscar o ofício de outra pessoa.
             */
            String preenchidoPorFuncao,
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
            /** O que o documento gravou naquele dia. Não é reescrito. */
            String cargo,
            /**
             * O ofício que o cadastro (Academy) diz hoje sobre esta pessoa.
             *
             * <p>O cargo do documento é o que foi digitado — ou preenchido pelo
             * perfil de acesso, antes de a função existir — e envelhece: o
             * rateio de um mês inteiro mostrava "Apontador" para quem é pedreiro
             * porque foi assim que entrou na época. Como o rateio é um retrato
             * de gente, e não uma cópia do documento, a função de quem a pessoa
             * é vale mais do que o rótulo de quando ela foi apontada.
             *
             * <p>Viaja ao lado do cargo, nunca no lugar dele: o RDO continua
             * dizendo o que disse, e quem quiser auditar o documento ainda o
             * encontra intacto. Nulo quando o cadastro não sabe o ofício —
             * pessoa somada à mão, ou sync que ainda não trouxe a função.
             */
            String funcaoCadastro
    ) {

        MaoDeObraDoRateio comFuncaoCadastro(String funcao) {
            return new MaoDeObraDoRateio(
                    colaboradorId,
                    nomeColaborador,
                    cargo,
                    funcao
            );
        }
    }
}

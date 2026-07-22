package com.projeto.cortex.intelligence.stavia.intent;

import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scores the question against every intent instead of returning the first match
 * in a fragile if/else cascade. Each intent contributes a score equal to the
 * number of its signal phrases that appear as anchored words in the question;
 * the highest score wins, ties are broken by the declared priority order, and a
 * confidence in {@code [0,1]} reports how unambiguous the winner was.
 *
 * <p>Matching is word-anchored (see {@link StaviaText}): the stem "comec" still
 * matches "começou", but "parada" no longer matches inside "preparada".
 */
@Component
public class StaviaIntentClassifier {

    /**
     * Intents in priority order (highest first). When two intents tie on score
     * the earlier one wins, preserving the historical cascade semantics
     * (programação and histórico outrank a bare RDO mention).
     */
    private static final List<IntentRule> RULES =
            List.of(
                    rule(
                            StaviaIntent.CONSULTAR_DOCUMENTOS_MENSAGEM_PENDENTES,
                            "documentos de mensagens pendentes",
                            "documentos de mensagem pendentes",
                            "anexos de mensagens pendentes",
                            "anexos de mensagem pendentes",
                            "mensagens pendentes de sincronizacao",
                            "documentos pendentes de sincronizacao"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_FORNECEDORES_COBRANCA_PENDENTE,
                            "fornecedores com cobrancas pendentes",
                            "fornecedores tem cobrancas pendentes",
                            "cobrancas pendentes de fornecedores",
                            "cobranca pendente por fornecedor"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_NOTAS_FISCAIS_VENCIDAS,
                            "notas fiscais vencidas",
                            "notas fiscais estao vencidas",
                            "nota fiscal vencida",
                            "nota fiscal esta vencida",
                            "faturas vencidas",
                            "faturas estao vencidas",
                            "fatura vencida",
                            "fatura esta vencida"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_HISTORICO_COMPRA,
                            "historico da compra",
                            "historico de compra",
                            "quem criou a compra",
                            "quem criou o pedido",
                            "alteracoes da compra",
                            "mudancas da compra"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_TOTAL_COMPRADO,
                            "total comprado",
                            "quanto foi comprado",
                            "valor comprado",
                            "comprado este mes",
                            "comprado no mes"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_HISTORICO,
                            "historico",
                            "alteracao",
                            "alteracoes",
                            "mudanca",
                            "mudancas",
                            "mudou",
                            "modificacao",
                            "modificacoes",
                            "editado",
                            "edicoes",
                            "ultimas 24",
                            "aconteceu",
                            "mensagem",
                            "mensagens",
                            "conversa",
                            "conversas",
                            "chat",
                            "arquivo compartilhado",
                            "anexo da conversa"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_RECEITA_EM_RISCO,
                            "receita em risco",
                            "risco de receita",
                            "receita perdida",
                            "perda de receita",
                            "producao rejeitada",
                            "retrabalho",
                            "paralisacao"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_MARGEM,
                            "margem",
                            "margem operacional",
                            "margem prevista",
                            "margem final",
                            "percentual de margem",
                            "resultado operacional"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_PDOR,
                            "pdor",
                            "previsao de receita",
                            "predicao de receita",
                            "receita prevista",
                            "receita final",
                            "captura de receita",
                            "shortfall",
                            "bater o contrato",
                            "atingir o contrato",
                            "vai bater a meta",
                            "probabilidade de nao atingir"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_RECEITA,
                            "receita",
                            "receita operacional",
                            "receita estimada",
                            "receita produzida",
                            "receita acumulada",
                            "receita validada",
                            "faturada",
                            "recebida"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_PREVISAO_FINANCEIRA,
                            "previsao financeira",
                            "resultado final",
                            "previsao de resultado",
                            "custo previsto",
                            "custo realizado",
                            "financeiro da obra"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_PRODUCAO,
                            "producao",
                            "quantidade executada",
                            "servico gerou",
                            "planejada versus executada",
                            "produzido",
                            "executado"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                            "onde o colaborador",
                            "colaborador estava",
                            "rateado",
                            "rateio",
                            "alocacao",
                            "alocacoes",
                            "horas da equipe",
                            "em quais obras"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_BANCO_HORAS,
                            "banco de horas",
                            "saldo de horas",
                            "saldo banco",
                            "credito de horas",
                            "debito de horas"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_FREQUENCIA,
                            "quem faltou",
                            "faltas",
                            "falta justificada",
                            "presenca",
                            "frequencia",
                            "hora extra",
                            "saida antecipada",
                            "atraso"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_PROGRAMACAO,
                            "programacao",
                            "programado",
                            "programada",
                            "planejado",
                            "planejada",
                            "gerado a partir",
                            "gerada a partir",
                            "origem",
                            "qual programacao"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_RDO,
                            "rdo",
                            "rdos",
                            "relatorio diario",
                            "relatorios diarios",
                            "quais rdos",
                            "listar rdos",
                            "pertencem",
                            "pertence"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_OBRA,
                            "mapa",
                            "geometria",
                            "dados geograficos",
                            "perimetro da obra",
                            "frente de trabalho no mapa",
                            "ponto operacional",
                            "coordenada no mapa"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_EQUIPE,
                            "equipe",
                            "colaborador",
                            "colaboradores",
                            "funcionario",
                            "funcionarios",
                            "profissional",
                            "profissionais",
                            "encarregado",
                            "engenheiro",
                            "operador",
                            "operadores",
                            "apontador",
                            "apontadores",
                            "maquinista",
                            "motorista",
                            "motoristas",
                            "servente",
                            "serventes",
                            "pedreiro",
                            "pedreiros",
                            "mao de obra",
                            "quem trabalhou",
                            "quantas pessoas",
                            "quantos trabalhadores"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_ATIVO,
                            "equipamento",
                            "ativo",
                            "maquina",
                            "veiculo",
                            "fresadora",
                            "caminhao",
                            "prefixo"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_OCORRENCIA,
                            "ocorrencia",
                            "incidente",
                            "problema",
                            "parada"
                    ),
                    rule(
                            StaviaIntent.RESUMIR_OBRA,
                            "resumo",
                            "resuma",
                            "visao geral"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_ESTADO_ATUAL,
                            "estado atual",
                            "situacao atual",
                            "como esta"
                    ),
                    ruleRequiring(
                            StaviaIntent.CONSULTAR_OBRA,
                            List.of("obra"),
                            List.of(
                                    "data",
                                    "quando",
                                    "comec",
                                    "inicio",
                                    "termin",
                                    "fim",
                                    "nome",
                                    "codigo",
                                    "cw",
                                    "contrato",
                                    "cliente",
                                    "cidade",
                                    "local",
                                    "rodovia",
                                    "cadastro",
                                    "cadastrada",
                                    "criada",
                                    "atualizada",
                                    "status",
                                    "qual obra",
                                    "que obra"
                            )
                    )
            );

    public StaviaIntent classify(String question) {
        return classifyDetailed(question).intent();
    }

    public StaviaClassification classifyDetailed(String question) {
        String normalized = StaviaText.normalize(question);

        IntentRule winner = null;
        int winnerScore = 0;
        int totalScore = 0;

        for (IntentRule candidate : RULES) {
            int score = candidate.score(normalized);

            if (score == 0) {
                continue;
            }

            totalScore += score;

            if (score > winnerScore) {
                winnerScore = score;
                winner = candidate;
            }
        }

        if (winner == null) {
            return new StaviaClassification(
                    StaviaIntent.DESCONHECIDA,
                    0.0
            );
        }

        return new StaviaClassification(
                winner.intent(),
                (double) winnerScore / totalScore
        );
    }

    private static IntentRule rule(
            StaviaIntent intent,
            String... signals
    ) {
        return new IntentRule(
                intent,
                List.of(),
                List.of(signals)
        );
    }

    private static IntentRule ruleRequiring(
            StaviaIntent intent,
            List<String> required,
            List<String> signals
    ) {
        return new IntentRule(intent, required, signals);
    }

    /**
     * A single intent's matching rule: it only fires when every {@code required}
     * term is present, and then scores by how many {@code signals} match.
     */
    private record IntentRule(
            StaviaIntent intent,
            List<String> required,
            List<String> signals
    ) {

        int score(String normalized) {
            for (String term : required) {
                if (!StaviaText.containsWord(normalized, term)) {
                    return 0;
                }
            }

            int matched = 0;

            for (String term : signals) {
                if (StaviaText.containsWord(normalized, term)) {
                    matched++;
                }
            }

            return matched;
        }
    }
}

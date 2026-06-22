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
                            StaviaIntent.CONSULTAR_PROGRAMACAO,
                            "programacao",
                            "programado",
                            "planejado"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_HISTORICO,
                            "historico",
                            "mudou",
                            "ultimas 24",
                            "aconteceu"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_RDO,
                            "rdo",
                            "relatorio diario"
                    ),
                    rule(
                            StaviaIntent.CONSULTAR_EQUIPE,
                            "equipe",
                            "colaborador",
                            "encarregado",
                            "engenheiro",
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
                            StaviaIntent.CONSULTAR_PDOC,
                            "pdoc",
                            "previsao de custo",
                            "risco de estouro",
                            "risco de custo",
                            "projecao de custo",
                            "custo final estimado",
                            "probabilidade de ultrapassar"
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

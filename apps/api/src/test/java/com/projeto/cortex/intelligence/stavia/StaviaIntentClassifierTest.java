package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaviaIntentClassifierTest {

    private final StaviaIntentClassifier classifier =
            new StaviaIntentClassifier();

    @Test
    void shouldPrioritizeProgrammingWhenQuestionAlsoMentionsRdo() {
        StaviaIntent intent =
                classifier.classify(
                        "De qual programação operacional "
                                + "cada RDO desta obra foi gerado?"
                );

        assertThat(intent)
                .isEqualTo(
                        StaviaIntent.CONSULTAR_PROGRAMACAO
                );
    }

    @Test
    void shouldStillClassifyDirectRdoQuestion() {
        StaviaIntent intent =
                classifier.classify(
                        "Quais RDOs pertencem a esta obra?"
                );

        assertThat(intent)
                .isEqualTo(
                        StaviaIntent.CONSULTAR_RDO
                );
    }

    @Test
    void shouldPrioritizeHistoryWhenQuestionAlsoMentionsRdo() {
        StaviaIntent intent =
                classifier.classify(
                        "Qual é o histórico de alterações "
                                + "dos RDOs desta obra?"
                );

        assertThat(intent)
                .isEqualTo(
                        StaviaIntent.CONSULTAR_HISTORICO
                );
    }

    @Test
    void shouldClassifyPortugueseIntentVariationsDistinctly() {
        assertThat(
                classifier.classify(
                        "Liste os RDOs vinculados a esta obra"
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_RDO);

        assertThat(
                classifier.classify(
                        "Quais mudanças ocorreram nos relatórios diários?"
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_HISTORICO);

        assertThat(
                classifier.classify(
                        "Qual RDO foi gerado a partir da programação?"
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_PROGRAMACAO);

        assertThat(
                classifier.classify(
                        "Há risco de estouro do orçamento segundo o PDOR?"
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_PDOR);
    }

    @Test
    void shouldPrioritizePdorCostRiskOverGenericOccurrenceRisk() {
        assertThat(
                classifier.classify(
                        "Qual é o risco de estouro de custos desta obra?"
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_PDOR);
    }


    @Test
    void shouldClassifyWorksiteDateQuestion() {
        assertThat(
                classifier.classify(
                        "Qual é a data da obra?"
                )
        ).isEqualTo(
                StaviaIntent.CONSULTAR_OBRA
        );
    }

    @Test
    void shouldClassifyWorksiteCodeQuestion() {
        assertThat(
                classifier.classify(
                        "Qual é o código CW desta obra?"
                )
        ).isEqualTo(
                StaviaIntent.CONSULTAR_OBRA
        );
    }

    @Test
    void shouldClassifyWorksiteStartQuestion() {
        assertThat(
                classifier.classify(
                        "Quando esta obra começou?"
                )
        ).isEqualTo(
                StaviaIntent.CONSULTAR_OBRA
        );
    }


    @Test
    void shouldClassifyWhoWorkedQuestion() {
        assertThat(
                classifier.classify(
                        "Quem trabalhou nesta obra?"
                )
        ).isEqualTo(
                StaviaIntent.CONSULTAR_EQUIPE
        );
    }

    @Test
    void shouldClassifyLaborCountQuestion() {
        assertThat(
                classifier.classify(
                        "Quantas pessoas foram registradas na mão de obra?"
                )
        ).isEqualTo(
                StaviaIntent.CONSULTAR_EQUIPE
        );
    }

    @Test
    void shouldClassifyOperationalRolesAsTeamQuestions() {
        assertThat(
                classifier.classify(
                        "Quem são os operadores desta obra?"
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_EQUIPE);

        assertThat(
                classifier.classify(
                        "Tem apontadores trabalhando hoje?"
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_EQUIPE);
    }


    @Test
    void shouldClassifyEquipmentQuestion() {
        assertThat(
                classifier.classify(
                        "Quais equipamentos foram usados nesta obra?"
                )
        ).isEqualTo(
                StaviaIntent.CONSULTAR_ATIVO
        );
    }

    @Test
    void shouldClassifyEquipmentPrefixQuestion() {
        assertThat(
                classifier.classify(
                        "Qual é o prefixo da fresadora?"
                )
        ).isEqualTo(
                StaviaIntent.CONSULTAR_ATIVO
        );
    }

    @Test
    void shouldNotClassifyOccurrenceFromTermBuriedInsideWord() {
        assertThat(
                classifier.classify(
                        "Esta área foi preparada para a próxima etapa?"
                )
        ).isNotEqualTo(
                StaviaIntent.CONSULTAR_OCORRENCIA
        );
    }

    @Test
    void shouldReportHigherConfidenceForUnambiguousQuestion() {
        StaviaClassification ambiguous =
                classifier.classifyDetailed(
                        "De qual programação operacional "
                                + "cada RDO desta obra foi gerado?"
                );

        StaviaClassification direct =
                classifier.classifyDetailed(
                        "Qual é a data da obra?"
                );

        assertThat(ambiguous.intent())
                .isEqualTo(
                        StaviaIntent.CONSULTAR_PROGRAMACAO
                );

        assertThat(direct.confidence())
                .isGreaterThan(ambiguous.confidence());
    }

    @Test
    void shouldReportZeroConfidenceForUnknownIntent() {
        StaviaClassification classification =
                classifier.classifyDetailed(
                        "Bom dia, tudo certo por aí?"
                );

        assertThat(classification.intent())
                .isEqualTo(StaviaIntent.DESCONHECIDA);

        assertThat(classification.confidence())
                .isEqualTo(0.0);
    }

}

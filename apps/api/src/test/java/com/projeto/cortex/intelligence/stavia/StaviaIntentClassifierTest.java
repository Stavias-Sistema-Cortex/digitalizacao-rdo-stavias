package com.projeto.cortex.intelligence.stavia;

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

}

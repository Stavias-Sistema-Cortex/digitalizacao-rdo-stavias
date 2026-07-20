package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalIntent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaviaOperationalIntentClassifierTest {

    private final StaviaOperationalIntentClassifier classifier =
            new StaviaOperationalIntentClassifier();

    @Test
    void shouldClassifyDelayedActivitiesQuestionInPortuguese() {
        assertEquals(
                StaviaOperationalIntent.LIST_DELAYED_ACTIVITIES,
                classifier.classify("Quais atividades estão atrasadas na obra?")
        );
    }

    @Test
    void shouldClassifyWeeklyReprogrammingQuestionInPortuguese() {
        assertEquals(
                StaviaOperationalIntent.GENERATE_WEEKLY_REPROGRAMMING,
                classifier.classify("Prepare uma reprogramação semanal")
        );
    }

    @Test
    void shouldClassifyDependencyQuestionInPortuguese() {
        assertEquals(
                StaviaOperationalIntent.LIST_DEPENDENCIES,
                classifier.classify("Quais atividades dependem da impermeabilização?")
        );
    }
}

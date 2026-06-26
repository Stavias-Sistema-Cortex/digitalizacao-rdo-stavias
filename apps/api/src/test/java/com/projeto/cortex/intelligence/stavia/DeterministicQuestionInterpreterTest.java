package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.Origin;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicQuestionInterpreterTest {

    private final DeterministicQuestionInterpreter interpreter =
            new DeterministicQuestionInterpreter(
                    new StaviaIntentClassifier(),
                    new StaviaQueryPlanner(new StaviaSemanticCatalog()));

    @Test
    void shouldProduceDeterministicInterpretation() {
        Optional<StaviaInterpretation> result =
                interpreter.interpret(new StaviaQuestion(
                        "Qual é o histórico de alterações dos RDOs?", "u1", "obra-1"));

        assertTrue(result.isPresent());
        assertEquals(StaviaIntent.CONSULTAR_HISTORICO, result.get().intent());
        assertEquals(Origin.DETERMINISTICO, result.get().origin());
        assertTrue(result.get().classification().confidence() > 0.0);
    }
}

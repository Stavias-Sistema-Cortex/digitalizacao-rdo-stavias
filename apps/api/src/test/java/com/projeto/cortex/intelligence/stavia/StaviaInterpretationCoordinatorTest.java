package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.Origin;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationCoordinator;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaviaInterpretationCoordinatorTest {

    private final DeterministicQuestionInterpreter deterministic =
            new DeterministicQuestionInterpreter(
                    new StaviaIntentClassifier(),
                    new StaviaQueryPlanner(new StaviaSemanticCatalog()));

    private StaviaQuestion q() {
        return new StaviaQuestion("Quais RDOs pertencem a esta obra?", "u1", "obra-1");
    }

    @Test
    void shouldUseDeterministicWhenModeIsDeterministic() {
        StaviaQuestionInterpreter llm = question -> {
            throw new AssertionError("LLM não deveria ser chamado");
        };
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "deterministic", 0.45);

        assertEquals(Origin.DETERMINISTICO, coordinator.interpret(q()).origin());
    }

    @Test
    void shouldFallBackWhenLlmReturnsEmpty() {
        StaviaQuestionInterpreter llm = question -> Optional.empty();
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "llm", 0.45);

        assertEquals(Origin.DETERMINISTICO, coordinator.interpret(q()).origin());
    }

    @Test
    void shouldFallBackWhenLlmThrows() {
        StaviaQuestionInterpreter llm = question -> {
            throw new RuntimeException("ollama down");
        };
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "llm", 0.45);

        assertEquals(Origin.DETERMINISTICO, coordinator.interpret(q()).origin());
    }

    @Test
    void shouldUseLlmWhenItSucceeds() {
        StaviaInterpretation llmInterpretation = new StaviaInterpretation(
                new StaviaClassification(StaviaIntent.CONSULTAR_EQUIPE, 0.9),
                StaviaQueryPlan.empty(), Origin.LLM);
        StaviaQuestionInterpreter llm = question -> Optional.of(llmInterpretation);
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "llm", 0.45);

        assertEquals(Origin.LLM, coordinator.interpret(q()).origin());
    }
}

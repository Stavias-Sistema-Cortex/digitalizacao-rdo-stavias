package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;

import java.util.Optional;

public interface StaviaQuestionInterpreter {
    Optional<StaviaInterpretation> interpret(StaviaQuestion question);
}

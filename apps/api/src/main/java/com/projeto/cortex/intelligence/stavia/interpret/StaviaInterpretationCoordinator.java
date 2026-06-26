package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

public class StaviaInterpretationCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StaviaInterpretationCoordinator.class);

    private final DeterministicQuestionInterpreter deterministic;
    private final StaviaQuestionInterpreter llm;
    private final String mode;
    private final double doubtThreshold;

    public StaviaInterpretationCoordinator(
            DeterministicQuestionInterpreter deterministic,
            StaviaQuestionInterpreter llm,
            String mode,
            double doubtThreshold
    ) {
        this.deterministic = deterministic;
        this.llm = llm;
        this.mode = mode == null ? "deterministic" : mode.toLowerCase(Locale.ROOT);
        this.doubtThreshold = doubtThreshold;
    }

    public StaviaInterpretation interpret(StaviaQuestion question) {
        StaviaInterpretation fallback = deterministic.interpret(question).orElseThrow();

        if (llm == null || "deterministic".equals(mode)) {
            return fallback;
        }

        if ("llm-on-doubt".equals(mode)
                && fallback.intent() != StaviaIntent.DESCONHECIDA
                && fallback.classification().confidence() >= doubtThreshold) {
            return fallback;
        }

        try {
            Optional<StaviaInterpretation> result = llm.interpret(question);
            if (result.isPresent()) {
                return result.get();
            }
            LOGGER.info("Intérprete LLM vazio; usando fallback determinístico.");
        } catch (RuntimeException exception) {
            LOGGER.warn("Intérprete LLM falhou ({}); usando fallback determinístico.",
                    exception.getMessage());
        }

        return fallback;
    }
}

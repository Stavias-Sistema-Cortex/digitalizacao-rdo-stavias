package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DeterministicQuestionInterpreter implements StaviaQuestionInterpreter {

    private final StaviaIntentClassifier classifier;
    private final StaviaQueryPlanner planner;

    public DeterministicQuestionInterpreter(
            StaviaIntentClassifier classifier,
            StaviaQueryPlanner planner
    ) {
        this.classifier = classifier;
        this.planner = planner;
    }

    @Override
    public Optional<StaviaInterpretation> interpret(StaviaQuestion question) {
        StaviaClassification classification =
                classifier.classifyDetailed(question.text());
        StaviaQueryPlan plan = planner.plan(question, classification);
        StaviaIntent effectiveIntent =
                planner.effectiveIntent(classification.intent(), plan);
        double effectiveConfidence = planner.effectiveConfidence(
                classification.confidence(), classification.intent(), plan);

        return Optional.of(new StaviaInterpretation(
                new StaviaClassification(effectiveIntent, effectiveConfidence),
                plan,
                Origin.DETERMINISTICO));
    }
}

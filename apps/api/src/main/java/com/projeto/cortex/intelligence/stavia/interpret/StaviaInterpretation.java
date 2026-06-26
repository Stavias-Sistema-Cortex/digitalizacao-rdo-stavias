package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;

public record StaviaInterpretation(
        StaviaClassification classification,
        StaviaQueryPlan plan,
        Origin origin
) {

    public StaviaInterpretation {
        if (classification == null) {
            throw new IllegalArgumentException("A classificação deve ser informada.");
        }
        plan = plan == null ? StaviaQueryPlan.empty() : plan;
        origin = origin == null ? Origin.DETERMINISTICO : origin;
    }

    public StaviaIntent intent() {
        return classification.intent();
    }
}

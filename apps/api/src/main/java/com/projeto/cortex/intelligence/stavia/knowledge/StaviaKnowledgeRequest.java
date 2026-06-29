package com.projeto.cortex.intelligence.stavia.knowledge;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;

import java.util.Set;

public record StaviaKnowledgeRequest(
        StaviaQuestion question,
        StaviaIntent intent,
        String worksiteId,
        Set<String> permissions,
        StaviaQueryPlan plan
) {

    public StaviaKnowledgeRequest {
        if (question == null) {
            throw new IllegalArgumentException(
                    "A pergunta deve ser informada."
            );
        }

        if (intent == null) {
            throw new IllegalArgumentException(
                    "A intenção deve ser informada."
            );
        }

        if (worksiteId == null || worksiteId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada."
            );
        }

        worksiteId = worksiteId.trim();

        permissions = permissions == null
                ? Set.of()
                : Set.copyOf(permissions);

        plan = plan == null
                ? StaviaQueryPlan.empty()
                : plan;
    }

    public StaviaKnowledgeRequest(
            StaviaQuestion question,
            StaviaIntent intent,
            String worksiteId,
            Set<String> permissions
    ) {
        this(
                question,
                intent,
                worksiteId,
                permissions,
                StaviaQueryPlan.empty()
        );
    }
}

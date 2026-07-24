package com.projeto.cortex.intelligence.stavia.knowledge;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;

import java.util.List;
import java.util.Map;

public record StaviaKnowledgeBundle(
        List<StaviaEvidence> evidences,
        Map<String, String> consultedSources,
        List<String> warnings
) {

    public StaviaKnowledgeBundle {
        evidences = evidences == null
                ? List.of()
                : List.copyOf(evidences);

        consultedSources = consultedSources == null
                ? Map.of()
                : Map.copyOf(consultedSources);

        warnings = warnings == null
                ? List.of()
                : List.copyOf(warnings);
    }

    public static StaviaKnowledgeBundle empty() {
        return new StaviaKnowledgeBundle(
                List.of(),
                Map.of(),
                List.of()
        );
    }
}

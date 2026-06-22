package com.projeto.cortex.intelligence.stavia.knowledge;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StaviaKnowledgeOrchestrator {

    private final List<StaviaKnowledgeSource> sources;

    public StaviaKnowledgeOrchestrator(
            List<StaviaKnowledgeSource> sources
    ) {
        this.sources = sources == null
                ? List.of()
                : List.copyOf(sources);
    }

    public StaviaKnowledgeBundle retrieve(
            StaviaKnowledgeRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "A requisição de conhecimento deve ser informada."
            );
        }

        Map<String, StaviaEvidence> uniqueEvidence =
                new LinkedHashMap<>();

        Map<String, String> consultedSources =
                new LinkedHashMap<>();

        List<String> warnings =
                new ArrayList<>();

        for (StaviaKnowledgeSource source : sources) {
            if (!source.supports(request)) {
                continue;
            }

            consultedSources.put(
                    source.sourceName(),
                    source.sourceVersion()
            );

            try {
                List<StaviaEvidence> retrieved =
                        source.retrieve(request);

                if (retrieved == null) {
                    warnings.add(
                            "A fonte "
                                    + source.sourceName()
                                    + " retornou resultado nulo."
                    );

                    continue;
                }

                for (StaviaEvidence evidence : retrieved) {
                    if (evidence == null) {
                        continue;
                    }

                    uniqueEvidence.putIfAbsent(
                            evidenceKey(evidence),
                            evidence
                    );
                }
            } catch (RuntimeException exception) {
                warnings.add(
                        "A fonte "
                                + source.sourceName()
                                + " não pôde ser consultada."
                );
            }
        }

        return new StaviaKnowledgeBundle(
                List.copyOf(uniqueEvidence.values()),
                Map.copyOf(consultedSources),
                List.copyOf(warnings)
        );
    }

    private String evidenceKey(
            StaviaEvidence evidence
    ) {
        return evidence.type()
                + ":"
                + evidence.id();
    }
}

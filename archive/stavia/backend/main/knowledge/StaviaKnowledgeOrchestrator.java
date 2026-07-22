package com.projeto.cortex.intelligence.stavia.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceKeys;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaKnowledgeSourceRegistry;

@Component
public class StaviaKnowledgeOrchestrator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    StaviaKnowledgeOrchestrator.class
            );

    private final List<StaviaKnowledgeSource> sources;
    private final StaviaKnowledgeSourceRegistry sourceRegistry;

    public StaviaKnowledgeOrchestrator(
            List<StaviaKnowledgeSource> sources
    ) {
        this.sources = sources == null
                ? List.of()
                : List.copyOf(sources);
        this.sourceRegistry = new StaviaKnowledgeSourceRegistry();
    }

    @Autowired
    public StaviaKnowledgeOrchestrator(
            List<StaviaKnowledgeSource> sources,
            ObjectProvider<StaviaKnowledgeSourceRegistry> sourceRegistry
    ) {
        this.sources = sources == null
                ? List.of()
                : List.copyOf(sources);
        this.sourceRegistry = sourceRegistry == null
                ? new StaviaKnowledgeSourceRegistry()
                : sourceRegistry.getIfAvailable(
                        StaviaKnowledgeSourceRegistry::new
                );
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

        List<StaviaKnowledgeSource> selectedSources =
                sourceRegistry.select(sources, request);

        LOGGER.info(
                "Orquestrador Stav.IA selecionou {} fonte(s). intent={} planDomain={} operation={}",
                selectedSources.size(),
                request.intent(),
                request.plan().domain(),
                request.plan().operation()
        );

        for (StaviaKnowledgeSource source : selectedSources) {
            if (source == null) {
                LOGGER.warn(
                        "Uma fonte de conhecimento nula foi ignorada."
                );
                continue;
            }

            try {
                consultedSources.put(
                        source.sourceName(),
                        source.sourceVersion()
                );

                long startedAt =
                        System.nanoTime();

                List<StaviaEvidence> retrieved =
                        source.retrieve(request);

                long durationMillis =
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startedAt
                        );

                if (retrieved == null) {
                    LOGGER.warn(
                            "A fonte de conhecimento {} retornou resultado nulo.",
                            source.sourceName()
                    );

                    warnings.add(
                            "A fonte "
                                    + source.sourceName()
                                    + " retornou resultado nulo."
                    );

                    continue;
                }

                LOGGER.info(
                        "Fonte Stav.IA consultada. source={} version={} durationMs={} evidenceCount={}",
                        source.sourceName(),
                        source.sourceVersion(),
                        durationMillis,
                        retrieved.size()
                );

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
                Throwable rootCause =
                        rootCause(exception);

                LOGGER.error(
                        "Falha ao consultar a fonte de conhecimento {} na versão {}. "
                                + "exceptionClass={} exceptionMessage={} "
                                + "rootCauseClass={} rootCauseMessage={}",
                        source.sourceName(),
                        source.sourceVersion(),
                        exception.getClass().getName(),
                        exception.getMessage(),
                        rootCause.getClass().getName(),
                        rootCause.getMessage(),
                        exception
                );

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
        return StaviaEvidenceKeys.key(evidence);
    }

    private Throwable rootCause(
            Throwable throwable
    ) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }
}

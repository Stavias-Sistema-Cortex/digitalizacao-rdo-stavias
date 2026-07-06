package com.projeto.cortex.intelligence.stavia.knowledge.registry;

import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class StaviaKnowledgeSourceRegistry {

    private static final String FLEXIBLE_CONTEXT_SOURCE =
            "contexto-operacional-flexivel";
    private static final String RDO_RECORD_SOURCE =
            "registros-rdo";

    public List<StaviaKnowledgeSource> select(
            List<StaviaKnowledgeSource> sources,
            StaviaKnowledgeRequest request
    ) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }

        List<StaviaKnowledgeSource> supported =
                sources.stream()
                        .filter(source -> source != null && source.supports(request))
                        .sorted(
                                Comparator.comparingInt(
                                        source -> source.descriptor().priority()
                                )
                        )
                        .toList();

        if (request.plan() == null
                || !request.plan().planned()
                || request.plan().requiredSources().isEmpty()) {
            return supported;
        }

        Set<String> requiredSources =
                request.plan().requiredSources()
                        .stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());

        Set<String> requestedAttributes =
                request.plan().requestedAttributes()
                        .stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());

        List<StaviaKnowledgeSource> matching =
                supported.stream()
                        .filter(source ->
                                sourceMatches(
                                        source.descriptor(),
                                        requiredSources,
                                        requestedAttributes
                                )
                        )
                        .toList();

        if (matching.isEmpty()) {
            return supported;
        }

        List<StaviaKnowledgeSource> selected = new ArrayList<>(matching);
        if (requiredSources.contains(RDO_RECORD_SOURCE)) {
            return List.copyOf(selected);
        }

        supported.stream()
                .filter(this::isFlexibleContextSource)
                .filter(source -> !selected.contains(source))
                .forEach(selected::add);

        return List.copyOf(selected);
    }

    private boolean isFlexibleContextSource(StaviaKnowledgeSource source) {
        return source != null
                && source.descriptor() != null
                && source.descriptor().name() != null
                && source.descriptor().name().equalsIgnoreCase(
                        FLEXIBLE_CONTEXT_SOURCE
                );
    }

    private boolean sourceMatches(
            StaviaSourceDescriptor descriptor,
            Set<String> requiredSources,
            Set<String> requestedAttributes
    ) {
        boolean sourceMatches =
                descriptor.name() != null
                        && requiredSources.contains(
                                descriptor.name().toLowerCase(Locale.ROOT)
                        );

        boolean attributeMatches =
                requestedAttributes.isEmpty()
                        || descriptor.attributes()
                                .stream()
                                .map(value ->
                                        value.toLowerCase(Locale.ROOT)
                                )
                                .anyMatch(requestedAttributes::contains);

        return sourceMatches && attributeMatches;
    }
}

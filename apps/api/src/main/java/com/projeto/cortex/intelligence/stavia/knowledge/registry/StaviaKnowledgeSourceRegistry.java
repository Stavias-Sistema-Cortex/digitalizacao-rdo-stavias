package com.projeto.cortex.intelligence.stavia.knowledge.registry;

import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class StaviaKnowledgeSourceRegistry {

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

        return matching.isEmpty() ? supported : matching;
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

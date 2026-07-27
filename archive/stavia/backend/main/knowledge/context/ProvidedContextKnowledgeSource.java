package com.projeto.cortex.intelligence.stavia.knowledge.context;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProvidedContextKnowledgeSource implements StaviaKnowledgeSource {

    private static final String CONTEXT_MARKER = "Contexto informado:";

    @Override
    public String sourceName() {
        return OperationalContextKnowledgeSource.SOURCE_NAME;
    }

    @Override
    public String sourceVersion() {
        return "1.0-contexto-informado";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(),
                Set.of("OBRA", "RDO", "CONTEXTO"),
                Set.of("data", "rdo", "obra", "contrato", "cw", "cidade", "rodovia"),
                Set.of("CONTEXTUALIZA"),
                Set.of(),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Contexto da interface",
                0,
                10
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null
                && request.question() != null
                && extractProvidedContext(request.question().text()) != null;
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        String providedContext = extractProvidedContext(request.question().text());
        if (providedContext == null) {
            return List.of();
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", request.worksiteId());
        attributes.put("contextoInformado", providedContext);

        return List.of(new StaviaEvidence(
                StaviaEvidenceTypes.CONTEXTO_OBRA,
                "contexto-informado-" + Integer.toUnsignedString(providedContext.hashCode()),
                "Contexto informado pela interface: " + providedContext,
                Instant.now(),
                true,
                attributes
        ));
    }

    private String extractProvidedContext(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int index = text.indexOf(CONTEXT_MARKER);
        if (index < 0) {
            return null;
        }
        String context = text.substring(index + CONTEXT_MARKER.length()).trim();
        return context.isBlank() ? null : context;
    }
}

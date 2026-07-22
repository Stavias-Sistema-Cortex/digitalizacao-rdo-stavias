package com.projeto.cortex.intelligence.stavia.knowledge;

import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;

import java.util.List;

public interface StaviaKnowledgeSource {

    String sourceName();

    String sourceVersion();

    default StaviaSourceDescriptor descriptor() {
        return StaviaSourceDescriptor.legacy(
                sourceName(),
                sourceVersion()
        );
    }

    boolean supports(
            StaviaKnowledgeRequest request
    );

    List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    );
}

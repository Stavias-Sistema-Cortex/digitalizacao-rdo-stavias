package com.projeto.cortex.intelligence.stavia.knowledge;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;

import java.util.List;

public interface StaviaKnowledgeSource {

    String sourceName();

    String sourceVersion();

    boolean supports(
            StaviaKnowledgeRequest request
    );

    List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    );
}

package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.intelligence.stavia.ontology.service.StaviaReasoningService;

public class StaviaQueryController {

    private final StaviaReasoningService reasoningService;

    public StaviaQueryController(StaviaReasoningService reasoningService) {
        this.reasoningService = reasoningService;
    }
}

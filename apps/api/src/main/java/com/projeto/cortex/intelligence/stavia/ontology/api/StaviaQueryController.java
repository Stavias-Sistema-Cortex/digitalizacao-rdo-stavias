package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.intelligence.stavia.ontology.service.StaviaReasoningService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StaviaQueryController {

    private final StaviaReasoningService reasoningService;

    public StaviaQueryController(StaviaReasoningService reasoningService) {
        this.reasoningService = reasoningService;
    }

    @PostMapping("/api/stavia/query")
    public StaviaOperationalQueryResponse query(
            @RequestBody StaviaOperationalQueryRequest request
    ) {
        return reasoningService.answer(request.userId(), request.query(), request.scope());
    }
}

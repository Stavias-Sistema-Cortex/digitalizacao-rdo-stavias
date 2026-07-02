package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.intelligence.stavia.ontology.model.WeeklyReprogramming;
import com.projeto.cortex.intelligence.stavia.ontology.service.StaviaReasoningService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/api/stavia/reprogramming")
    public WeeklyReprogramming reprogramming(
            @RequestBody StaviaReprogrammingRequest request
    ) {
        return reasoningService.generateWeeklyReprogramming(
                request.userId(),
                request.obraId(),
                request.period(),
                request.targetRecoveryDays()
        );
    }

    @GetMapping("/api/stavia/suggestions")
    public List<String> suggestions(
            @RequestParam(required = false) String obraId
    ) {
        return reasoningService.suggestions(obraId);
    }
}

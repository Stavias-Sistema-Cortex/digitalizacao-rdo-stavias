package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.intelligence.stavia.ontology.model.WeeklyReprogramming;
import com.projeto.cortex.intelligence.stavia.ontology.service.StaviaReasoningService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta operacional de raciocínio da Stav.IA. A identidade vem sempre do
 * usuário autenticado (nunca do corpo da requisição, evitando falsificação) e o
 * acesso segue o escopo de obra: com obraId, exige vínculo/permissão à obra;
 * sem obraId (consulta global), exige o papel Alfa.
 */
@RestController
public class StaviaQueryController {

    private final StaviaReasoningService reasoningService;
    private final CurrentUserService currentUserService;

    public StaviaQueryController(
            StaviaReasoningService reasoningService,
            CurrentUserService currentUserService
    ) {
        this.reasoningService = reasoningService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/stavia/query")
    public StaviaOperationalQueryResponse query(
            @RequestBody StaviaOperationalQueryRequest request
    ) {
        String userId = currentUserService.requireUserId();
        String obraId = request.scope() == null ? null : request.scope().obraId();
        exigirEscopo(obraId);
        return reasoningService.answer(userId, request.query(), request.scope());
    }

    @PostMapping("/api/stavia/reprogramming")
    public WeeklyReprogramming reprogramming(
            @RequestBody StaviaReprogrammingRequest request
    ) {
        String userId = currentUserService.requireUserId();
        exigirEscopo(request.obraId());
        return reasoningService.generateWeeklyReprogramming(
                userId,
                request.obraId(),
                request.period(),
                request.targetRecoveryDays()
        );
    }

    @GetMapping("/api/stavia/suggestions")
    public List<String> suggestions(
            @RequestParam(required = false) String obraId
    ) {
        exigirEscopo(obraId);
        return reasoningService.suggestions(obraId);
    }

    /** Com obra, exige acesso à obra; sem obra (escopo global), exige Alfa. */
    private void exigirEscopo(String obraId) {
        if (obraId != null && !obraId.isBlank()) {
            currentUserService.requireWorksiteAccess(obraId);
        } else {
            currentUserService.requireAdmin();
        }
    }
}

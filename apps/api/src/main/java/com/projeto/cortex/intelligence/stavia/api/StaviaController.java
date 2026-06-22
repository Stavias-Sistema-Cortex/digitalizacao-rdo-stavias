package com.projeto.cortex.intelligence.stavia.api;

import com.projeto.cortex.intelligence.stavia.StaviaQueryResult;
import com.projeto.cortex.intelligence.stavia.StaviaQueryService;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
public class StaviaController {

    private final StaviaQueryService queryService;

    public StaviaController(
            StaviaQueryService queryService
    ) {
        this.queryService = queryService;
    }

    @PostMapping("/api/stavia/consultas")
    public StaviaQueryResult consultar(
            @RequestBody StaviaConsultaRequest request
    ) {
        // Permissions and worksite access are resolved by the StaviaAccessPolicy
        // inside the query service, derived from the user — the controller no
        // longer fabricates the required permission for every caller.
        StaviaQuestion question =
                new StaviaQuestion(
                        request.pergunta(),
                        request.usuarioId(),
                        request.obraId()
                );

        return queryService.query(question);
    }
}

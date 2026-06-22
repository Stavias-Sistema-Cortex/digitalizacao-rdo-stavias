package com.projeto.cortex.intelligence.stavia.api;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.StaviaQueryResult;
import com.projeto.cortex.intelligence.stavia.StaviaQueryService;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

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
        StaviaQuestion question =
                new StaviaQuestion(
                        request.pergunta(),
                        request.usuarioId(),
                        request.obraId()
                );

        return queryService.query(
                question,
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }
}

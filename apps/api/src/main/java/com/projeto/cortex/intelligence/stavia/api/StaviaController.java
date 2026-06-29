package com.projeto.cortex.intelligence.stavia.api;

import com.projeto.cortex.intelligence.stavia.StaviaQueryResult;
import com.projeto.cortex.intelligence.stavia.StaviaQueryService;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
public class StaviaController {

    private final StaviaQueryService queryService;
    private final ObjectProvider<StaviaSnapshotService> snapshotService;

    @Autowired
    public StaviaController(
            StaviaQueryService queryService,
            ObjectProvider<StaviaSnapshotService> snapshotService
    ) {
        this.queryService = queryService;
        this.snapshotService = snapshotService;
    }

    public StaviaController(
            StaviaQueryService queryService
    ) {
        this(queryService, null);
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
                        questionWithContext(request),
                        request.usuarioId(),
                        firstNonBlank(
                                request.obraId(),
                                request.ultimoObraId()
                        )
                );

        return queryService.query(question);
    }

    @GetMapping("/api/stavia/snapshot")
    public StaviaSnapshotResponse snapshot() {
        StaviaSnapshotService service = snapshotService == null
                ? null
                : snapshotService.getIfAvailable();

        if (service == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "O serviço de snapshot da Stav.IA não está disponível."
            );
        }

        return service.buildSnapshot();
    }

    private String questionWithContext(StaviaConsultaRequest request) {
        StringBuilder question = new StringBuilder(request.pergunta());

        if (
                !isBlank(request.obraId())
                        || !isBlank(request.rdoId())
        ) {
            question.append("\nContexto ontológico selecionado:");

            if (!isBlank(request.obraId())) {
                question.append(" obraId=")
                        .append(request.obraId().trim());
            }

            if (!isBlank(request.rdoId())) {
                question.append(" rdoId=")
                        .append(request.rdoId().trim());
            }
        }

        String context = request.contextoSelecionado();
        if (context != null && !context.isBlank()) {
            question.append("\nContexto informado: ")
                    .append(context.trim());
        }

        return question.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }

        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

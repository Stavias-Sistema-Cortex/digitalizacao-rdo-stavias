package com.projeto.cortex.rdos;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
public class RdoController {

    private static final int MAX_WORKFORCE_ROWS = 26;
    private static final int MAX_SERVICE_ROWS = 21;
    private static final int MAX_MATERIAL_ROWS = 30;
    private static final int MAX_ATTACHMENT_ROWS = 5;

    private final RdoService service;
    private final RdoQueryService queryService;
    private final RdoDraftUpdateService draftUpdateService;
    private final RdoWorkflowService workflowService;
    private final CurrentUserService currentUserService;

    public RdoController(
            RdoService service,
            RdoQueryService queryService,
            RdoDraftUpdateService draftUpdateService,
            RdoWorkflowService workflowService,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.queryService = queryService;
        this.draftUpdateService = draftUpdateService;
        this.workflowService = workflowService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/rdos")
    @ResponseStatus(HttpStatus.CREATED)
    public RdoResponse criar(@RequestBody RdoCreateRequest request) {
        currentUserService.requireWorksiteAccess(
                request == null ? null : request.obraId()
        );
        requirePrintableCollectionLimits(request);
        return service.criarRascunho(request);
    }

    @GetMapping("/api/rdos")
    public List<RdoResumoResponse> listar(
            @RequestParam String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate data
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return queryService.listarPorObra(obraId, data);
    }

    @GetMapping("/api/rdos/{id}")
    public RdoResponse buscarPorId(@PathVariable String id) {
        currentUserService.requireRdoAccess(id);
        return queryService.buscarPorId(id);
    }

    @PutMapping("/api/rdos/{id}")
    public RdoResponse atualizarRascunho(
            @PathVariable String id,
            @RequestBody RdoCreateRequest request
    ) {
        currentUserService.requireRdoWorksiteAccess(
                id,
                request == null ? null : request.obraId()
        );
        requirePrintableCollectionLimits(request);
        return draftUpdateService.atualizarRascunho(id, request);
    }

    @PostMapping("/api/rdos/{id}/enviar")
    public RdoResponse enviar(@PathVariable String id) {
        currentUserService.requireRdoAccess(id);
        return workflowService.enviar(id);
    }

    private void requirePrintableCollectionLimits(RdoCreateRequest request) {
        if (request == null) {
            return;
        }
        requireLimit(request.maoObra(), MAX_WORKFORCE_ROWS, "maoObra");
        requireLimit(
                request.servicosExecutados(), MAX_SERVICE_ROWS,
                "servicosExecutados"
        );
        requireLimit(request.materiais(), MAX_MATERIAL_ROWS, "materiais");
        requireLimit(request.attachments(), MAX_ATTACHMENT_ROWS, "attachments");
    }

    private void requireLimit(List<?> values, int limit, String field) {
        if (values != null && values.size() > limit) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " excede o limite permitido."
            );
        }
    }
}

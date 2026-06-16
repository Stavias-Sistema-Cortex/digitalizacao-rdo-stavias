package com.projeto.cortex.rdos;

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

import java.time.LocalDate;
import java.util.List;

@RestController
public class RdoController {

    private final RdoService service;
    private final RdoQueryService queryService;
    private final RdoDraftUpdateService draftUpdateService;
    private final RdoWorkflowService workflowService;

    public RdoController(
            RdoService service,
            RdoQueryService queryService,
            RdoDraftUpdateService draftUpdateService,
            RdoWorkflowService workflowService
    ) {
        this.service = service;
        this.queryService = queryService;
        this.draftUpdateService = draftUpdateService;
        this.workflowService = workflowService;
    }

    @PostMapping("/api/rdos")
    @ResponseStatus(HttpStatus.CREATED)
    public RdoResponse criar(@RequestBody RdoCreateRequest request) {
        return service.criarRascunho(request);
    }

    @GetMapping("/api/rdos")
    public List<RdoResumoResponse> listar(
            @RequestParam String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate data
    ) {
        return queryService.listarPorObra(obraId, data);
    }

    @GetMapping("/api/rdos/{id}")
    public RdoResponse buscarPorId(@PathVariable String id) {
        return queryService.buscarPorId(id);
    }

    @PutMapping("/api/rdos/{id}")
    public RdoResponse atualizarRascunho(
            @PathVariable String id,
            @RequestBody RdoCreateRequest request
    ) {
        return draftUpdateService.atualizarRascunho(id, request);
    }

    @PostMapping("/api/rdos/{id}/enviar")
    public RdoResponse enviar(@PathVariable String id) {
        return workflowService.enviar(id);
    }
}

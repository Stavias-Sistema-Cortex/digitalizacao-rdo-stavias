package com.projeto.cortex.rdos;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionAudit;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionRequest;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final RdoDeletionService deletionService;
    private final CurrentUserService currentUserService;
    private final RdoExecutionDecisionService executionDecisionService;

    public RdoController(
            RdoService service,
            RdoQueryService queryService,
            RdoDraftUpdateService draftUpdateService,
            RdoWorkflowService workflowService,
            RdoDeletionService deletionService,
            CurrentUserService currentUserService,
            RdoExecutionDecisionService executionDecisionService
    ) {
        this.service = service;
        this.queryService = queryService;
        this.draftUpdateService = draftUpdateService;
        this.workflowService = workflowService;
        this.deletionService = deletionService;
        this.currentUserService = currentUserService;
        this.executionDecisionService = executionDecisionService;
    }

    @PostMapping("/api/rdos")
    @ResponseStatus(HttpStatus.CREATED)
    public RdoResponse criar(@RequestBody RdoCreateRequest request) {
        currentUserService.requireWorksiteAccess(
                request == null ? null : request.obraId()
        );
        RdoPrintableCollectionLimits.requireWithinTemplateCapacity(request);
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
        RdoPrintableCollectionLimits.requireWithinTemplateCapacity(request);
        return draftUpdateService.atualizarRascunho(id, request);
    }

    @PostMapping("/api/rdos/{id}/enviar")
    public RdoResponse enviar(@PathVariable String id) {
        currentUserService.requireRdoAccess(id);
        return workflowService.enviar(id);
    }

    @PostMapping("/api/rdos/{id}/execucoes/{executionId}/decisoes")
    public RdoResponse decidirExecucaoServico(
            @PathVariable String id,
            @PathVariable String executionId,
            @RequestBody RdoExecutionDecisionRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        currentUserService.requireRdoAccess(id);
        return executionDecisionService.decidir(
                id,
                executionId,
                request,
                new RdoExecutionDecisionAudit(
                        currentUserService.requireUserId(),
                        null,
                        normalizarCorrelacao(correlationId),
                        "ONLINE"
                )
        );
    }

    /**
     * Apagar existe para que ninguém precise abrir o console do banco.
     *
     * <p>Apagar por fora é o que quebra a sincronização: o servidor perde
     * linhas que a fila do aparelho ainda referencia, e cada mutação órfã vira
     * um conflito novo no ciclo seguinte. Por aqui o cliente fica sabendo e
     * limpa a própria fila junto.
     *
     * <p>A autorização fina é do serviço, que precisa ler o status do RDO
     * antes de decidir: rascunho quem alcança a obra apaga, enviado só o Alfa.
     */
    @DeleteMapping("/api/rdos/{id}")
    public RdoDeletionResponse apagar(@PathVariable String id) {
        return deletionService.apagar(id);
    }

    private String normalizarCorrelacao(String correlationId) {
        return correlationId == null || correlationId.isBlank()
                ? null
                : correlationId.strip();
    }

}

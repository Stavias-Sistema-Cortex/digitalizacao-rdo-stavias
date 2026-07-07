package com.projeto.cortex.pdor;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class PdorController {

    private final PdorApplicationService service;
    private final CurrentUserService currentUserService;

    public PdorController(
            PdorApplicationService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/obras/{obraId}/pdor/calcular")
    public PdorResultadoResponse calcular(
            @PathVariable String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dataReferencia,
            @RequestParam(required = false) String tipoDisparo,
            @RequestParam(required = false) String eventoOrigemId
    ) {
        currentUserService.requireAdmin();
        return service.calcular(
                obraId,
                dataReferencia,
                PdorTriggerType.from(tipoDisparo),
                eventoOrigemId
        );
    }

    @GetMapping("/api/obras/{obraId}/pdor/atual")
    public PdorResultadoResponse atual(@PathVariable String obraId) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.buscarAtual(obraId);
    }

    @GetMapping("/api/obras/{obraId}/pdor/historico")
    public PdorHistoricoResponse historico(
            @PathVariable String obraId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.buscarHistorico(obraId, page, size);
    }
}

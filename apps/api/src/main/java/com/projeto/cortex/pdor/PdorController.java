package com.projeto.cortex.pdor;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class PdorController {

    private static final long MAX_HISTORY_OFFSET = 1_000_000L;

    private final PdorApplicationService service;
    private final FinancialAccessService financialAccessService;

    public PdorController(
            PdorApplicationService service,
            FinancialAccessService financialAccessService
    ) {
        this.service = service;
        this.financialAccessService = financialAccessService;
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
        financialAccessService.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.calcular(
                obraId,
                dataReferencia,
                PdorTriggerType.from(tipoDisparo),
                eventoOrigemId
        );
    }

    @GetMapping("/api/obras/{obraId}/pdor/atual")
    public PdorResultadoResponse atual(@PathVariable String obraId) {
        financialAccessService.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.buscarAtual(obraId);
    }

    @GetMapping("/api/obras/{obraId}/pdor/historico")
    public PdorHistoricoResponse historico(
            @PathVariable String obraId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        validateHistoryPage(page, size);
        financialAccessService.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.buscarHistorico(obraId, page, size);
    }

    private void validateHistoryPage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page não pode ser negativo.");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size deve estar entre 1 e 100.");
        }
        long offset = Math.multiplyExact((long) page, size);
        if (offset > MAX_HISTORY_OFFSET) {
            throw new IllegalArgumentException("page excede o limite permitido.");
        }
    }
}

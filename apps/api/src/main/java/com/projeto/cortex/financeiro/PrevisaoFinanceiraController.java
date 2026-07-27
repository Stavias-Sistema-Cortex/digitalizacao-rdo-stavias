package com.projeto.cortex.financeiro;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.pdor.PdorHistoricoResponse;
import com.projeto.cortex.pdor.PdorResultadoResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class PrevisaoFinanceiraController {

    private final PrevisaoFinanceiraService service;
    private final FinancialAccessService financialAccessService;

    public PrevisaoFinanceiraController(
            PrevisaoFinanceiraService service,
            FinancialAccessService financialAccessService
    ) {
        this.service = service;
        this.financialAccessService = financialAccessService;
    }

    @PostMapping("/api/obras/{obraId}/previsao-financeira/calcular")
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
                tipoDisparo,
                eventoOrigemId
        );
    }

    @GetMapping("/api/obras/{obraId}/previsao-financeira/atual")
    public PdorResultadoResponse atual(@PathVariable String obraId) {
        financialAccessService.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.buscarAtual(obraId);
    }

    @GetMapping("/api/obras/{obraId}/previsao-financeira/historico")
    public PdorHistoricoResponse historico(
            @PathVariable String obraId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        financialAccessService.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.buscarHistorico(obraId, page, size);
    }
}

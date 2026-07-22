package com.projeto.cortex.financeiro;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/financeiro")
public class ResultadoOperacionalFinanceiroController {
    private final ResultadoOperacionalFinanceiroService service;
    private final FinancialAccessService access;
    public ResultadoOperacionalFinanceiroController(ResultadoOperacionalFinanceiroService service, FinancialAccessService access) { this.service = service; this.access = access; }
    @GetMapping("/resultado-operacional")
    public ResultadoOperacionalFinanceiroResponse buscar(@RequestParam String obraId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        access.requirePermission(obraId, FinancialPermission.FINANCEIRO_VISUALIZAR);
        return service.buscar(obraId, de, ate);
    }
}


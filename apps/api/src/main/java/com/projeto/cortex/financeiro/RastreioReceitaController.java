package com.projeto.cortex.financeiro;

import com.projeto.cortex.auth.CurrentUserService;
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
public class RastreioReceitaController {
    private final RastreioReceitaService service; private final FinancialAccessService access; private final CurrentUserService currentUser;
    public RastreioReceitaController(RastreioReceitaService service, FinancialAccessService access, CurrentUserService currentUser) { this.service = service; this.access = access; this.currentUser = currentUser; }
    @GetMapping("/rastreio-receita")
    public RastreioReceitaResponse buscar(@RequestParam(required = false) String obraId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return service.buscar(access.allowedObraIds(currentUser.requireUserId(), FinancialPermission.FINANCEIRO_VISUALIZAR), obraId, de, ate);
    }
}


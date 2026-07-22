package com.projeto.cortex.financeiro;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/financeiro/rastreio-receita")
public class RastreioReceitaController {

    private final RastreioReceitaService service;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public RastreioReceitaController(
            RastreioReceitaService service,
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    public RastreioReceitaResponse buscar(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate ate
    ) {
        String userId = currentUser.requireUserId();
        Set<String> scope;
        if (obraId != null && !obraId.isBlank()) {
            String normalizedWorksite = uuid(obraId);
            access.requirePermission(
                    normalizedWorksite, FinancialPermission.FINANCEIRO_VISUALIZAR
            );
            scope = Set.of(normalizedWorksite);
            obraId = normalizedWorksite;
        } else {
            scope = access.allowedObraIds(
                    userId, FinancialPermission.FINANCEIRO_VISUALIZAR
            );
        }
        return service.buscar(scope, obraId, de, ate);
    }

    @GetMapping("/{executionId}")
    public RastreioReceitaEvidenceResponse evidencia(
            @PathVariable String executionId
    ) {
        String userId = currentUser.requireUserId();
        Set<String> scope = access.allowedObraIds(
                userId, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.evidencia(scope, executionId);
    }

    private String uuid(String raw) {
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "REVENUE_TRACE_WORKSITE_INVALID"
            );
        }
    }
}

package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FinancialGrantController {

    private final FinancialGrantService service;
    private final CurrentUserService currentUserService;

    public FinancialGrantController(
            FinancialGrantService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/obras/{obraId}/permissoes-financeiras")
    public List<FinancialGrantResponse> list(@PathVariable String obraId) {
        currentUserService.requireAlfa();
        return service.list(obraId);
    }

    @GetMapping("/api/financeiro/unidades/{unidadeId}/permissoes")
    public List<FinancialGrantResponse> listByUnit(
            @PathVariable String unidadeId
    ) {
        currentUserService.requireAlfa();
        return service.listByUnit(unidadeId);
    }

    @PostMapping("/api/obras/{obraId}/permissoes-financeiras")
    public FinancialGrantResponse grant(
            @PathVariable String obraId,
            @RequestBody FinancialGrantRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        return service.grant(
                obraId,
                request == null ? null : request.colaboradorId(),
                request == null ? null : request.permissao(),
                request == null ? null : request.justificativa(),
                actorId
        );
    }

    @PostMapping("/api/financeiro/unidades/{unidadeId}/permissoes")
    public FinancialGrantResponse grantUnit(
            @PathVariable String unidadeId,
            @RequestBody FinancialGrantRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        return service.grantUnit(
                unidadeId,
                request == null ? null : request.colaboradorId(),
                request == null ? null : request.permissao(),
                request == null ? null : request.justificativa(),
                actorId
        );
    }

    @DeleteMapping(
            "/api/obras/{obraId}/permissoes-financeiras/"
                    + "{colaboradorId}/{permissao}"
    )
    public FinancialGrantResponse revoke(
            @PathVariable String obraId,
            @PathVariable String colaboradorId,
            @PathVariable FinancialPermission permissao,
            @RequestBody(required = false) FinancialGrantRevokeRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        return service.revoke(
                obraId,
                colaboradorId,
                permissao,
                request == null ? null : request.justificativa(),
                actorId
        );
    }

    @DeleteMapping(
            "/api/financeiro/unidades/{unidadeId}/permissoes/"
                    + "{colaboradorId}/{permissao}"
    )
    public FinancialGrantResponse revokeUnit(
            @PathVariable String unidadeId,
            @PathVariable String colaboradorId,
            @PathVariable FinancialPermission permissao,
            @RequestBody(required = false) FinancialGrantRevokeRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        return service.revokeUnit(
                unidadeId,
                colaboradorId,
                permissao,
                request == null ? null : request.justificativa(),
                actorId
        );
    }
}

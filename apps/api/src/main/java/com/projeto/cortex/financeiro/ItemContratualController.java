package com.projeto.cortex.financeiro;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ItemContratualController {

    private final ItemContratualService service;
    private final FinancialAccessService financialAccessService;

    public ItemContratualController(
            ItemContratualService service,
            FinancialAccessService financialAccessService
    ) {
        this.service = service;
        this.financialAccessService = financialAccessService;
    }

    @PostMapping("/api/obras/{obraId}/itens-contratuais")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemContratualResponse criar(
            @PathVariable String obraId,
            @RequestBody ItemContratualRequest request
    ) {
        financialAccessService.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
        return service.criar(obraId, request);
    }

    @GetMapping("/api/obras/{obraId}/itens-contratuais")
    public List<ItemContratualResponse> listar(@PathVariable String obraId) {
        financialAccessService.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listar(obraId);
    }
}

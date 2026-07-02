package com.projeto.cortex.financeiro;

import com.projeto.cortex.auth.CurrentUserService;
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
    private final CurrentUserService currentUserService;

    public ItemContratualController(
            ItemContratualService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/obras/{obraId}/itens-contratuais")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemContratualResponse criar(
            @PathVariable String obraId,
            @RequestBody ItemContratualRequest request
    ) {
        currentUserService.requireAdmin();
        return service.criar(obraId, request);
    }

    @GetMapping("/api/obras/{obraId}/itens-contratuais")
    public List<ItemContratualResponse> listar(@PathVariable String obraId) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.listar(obraId);
    }
}

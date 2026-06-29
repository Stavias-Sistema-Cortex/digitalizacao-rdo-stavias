package com.projeto.cortex.financeiro;

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

    public ItemContratualController(ItemContratualService service) {
        this.service = service;
    }

    @PostMapping("/api/obras/{obraId}/itens-contratuais")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemContratualResponse criar(
            @PathVariable String obraId,
            @RequestBody ItemContratualRequest request
    ) {
        return service.criar(obraId, request);
    }

    @GetMapping("/api/obras/{obraId}/itens-contratuais")
    public List<ItemContratualResponse> listar(@PathVariable String obraId) {
        return service.listar(obraId);
    }
}

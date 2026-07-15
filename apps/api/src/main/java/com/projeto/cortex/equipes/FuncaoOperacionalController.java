package com.projeto.cortex.equipes;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/funcoes-operacionais")
public class FuncaoOperacionalController {

    private final EquipeService service;
    private final CurrentUserService currentUserService;

    public FuncaoOperacionalController(
            EquipeService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<FuncaoOperacionalResponse> listar(
            @RequestParam(defaultValue = "false") boolean incluirInativas
    ) {
        return service.listarFuncoes(incluirInativas);
    }

    @PostMapping
    public FuncaoOperacionalResponse criar(
            @RequestBody FuncaoOperacionalRequest request
    ) {
        currentUserService.requireAlfa();
        return service.criarFuncao(request);
    }

    @PutMapping("/{id}")
    public FuncaoOperacionalResponse atualizar(
            @PathVariable String id,
            @RequestBody FuncaoOperacionalRequest request
    ) {
        currentUserService.requireAlfa();
        return service.atualizarFuncao(id, request);
    }
}

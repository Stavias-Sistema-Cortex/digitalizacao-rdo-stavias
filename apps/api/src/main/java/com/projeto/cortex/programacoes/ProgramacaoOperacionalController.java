package com.projeto.cortex.programacoes;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ProgramacaoOperacionalController {

    private final ProgramacaoOperacionalService programacaoService;
    private final CurrentUserService currentUserService;

    public ProgramacaoOperacionalController(
            ProgramacaoOperacionalService programacaoService,
            CurrentUserService currentUserService
    ) {
        this.programacaoService = programacaoService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/programacoes")
    public List<ProgramacaoOperacionalResponse> listarProgramacoes(
            @RequestParam(required = false) String query
    ) {
        currentUserService.requireAdmin();
        return programacaoService.listarProgramacoes(query);
    }

    @PostMapping("/api/programacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramacaoOperacionalResponse criarProgramacao(
            @RequestBody ProgramacaoOperacionalRequest request
    ) {
        currentUserService.requireAdmin();
        return programacaoService.criarProgramacao(request);
    }
}

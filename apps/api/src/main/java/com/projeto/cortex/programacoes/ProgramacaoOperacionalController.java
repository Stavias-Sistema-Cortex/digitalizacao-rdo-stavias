package com.projeto.cortex.programacoes;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ProgramacaoOperacionalController {

    private final ProgramacaoOperacionalService programacaoService;

    public ProgramacaoOperacionalController(ProgramacaoOperacionalService programacaoService) {
        this.programacaoService = programacaoService;
    }

    @GetMapping("/api/programacoes")
    public List<ProgramacaoOperacionalResponse> listarProgramacoes(
            @RequestParam(required = false) String query
    ) {
        return programacaoService.listarProgramacoes(query);
    }

    @PostMapping("/api/programacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramacaoOperacionalResponse criarProgramacao(
            @RequestBody ProgramacaoOperacionalRequest request
    ) {
        return programacaoService.criarProgramacao(request);
    }
}

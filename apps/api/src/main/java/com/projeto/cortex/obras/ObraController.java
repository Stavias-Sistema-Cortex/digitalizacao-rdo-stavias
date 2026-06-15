package com.projeto.cortex.obras;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ObraController {

    private final ObraService obraService;

    public ObraController(ObraService obraService) {
        this.obraService = obraService;
    }

    @GetMapping("/api/obras")
    public List<ObraResponse> listarObras(@RequestParam(required = false) String query) {
        return obraService.listarObras(query);
    }

    @PostMapping("/api/obras")
    @ResponseStatus(HttpStatus.CREATED)
    public ObraResponse criarObra(@RequestBody ObraRequest request) {
        return obraService.criarObra(request);
    }
}

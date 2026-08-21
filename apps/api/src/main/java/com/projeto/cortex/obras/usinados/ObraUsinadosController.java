package com.projeto.cortex.obras.usinados;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObraUsinadosController {

    private final ObraUsinadosService service;

    public ObraUsinadosController(ObraUsinadosService service) {
        this.service = service;
    }

    @GetMapping("/api/obras/{obraId}/usinados")
    public ObraUsinadosResponse buscarUsinados(@PathVariable String obraId) {
        return service.buscarUsinados(obraId);
    }
}

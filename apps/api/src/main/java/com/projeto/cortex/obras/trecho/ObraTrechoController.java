package com.projeto.cortex.obras.trecho;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class ObraTrechoController {

    private final ObraTrechoService service;

    public ObraTrechoController(ObraTrechoService service) {
        this.service = service;
    }

    /**
     * @param de  primeira data do período, opcional
     * @param ate última data do período, opcional
     */
    @GetMapping("/api/obras/{obraId}/trecho")
    public ObraTrechoResponse buscarTrecho(
            @PathVariable String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate
    ) {
        return service.buscarTrecho(obraId, de, ate);
    }
}

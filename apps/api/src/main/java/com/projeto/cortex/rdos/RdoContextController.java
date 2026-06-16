package com.projeto.cortex.rdos;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class RdoContextController {

    private final RdoContextService service;

    public RdoContextController(RdoContextService service) {
        this.service = service;
    }

    @GetMapping("/api/rdos/contexto")
    public RdoContextResponse contexto(
            @RequestParam String obraId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate data
    ) {
        return service.buscarContexto(obraId, data);
    }
}

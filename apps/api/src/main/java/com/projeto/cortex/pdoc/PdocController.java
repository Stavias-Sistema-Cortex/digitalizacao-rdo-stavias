package com.projeto.cortex.pdoc;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class PdocController {

    private final PdocApplicationService service;

    public PdocController(PdocApplicationService service) {
        this.service = service;
    }

    @PostMapping("/api/obras/{obraId}/pdoc/calcular")
    public PdocResultadoResponse calcular(
            @PathVariable String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dataReferencia,
            @RequestParam(required = false) String tipoDisparo,
            @RequestParam(required = false) String eventoOrigemId
    ) {
        return service.calcular(
                obraId,
                dataReferencia,
                PdocTriggerType.from(tipoDisparo),
                eventoOrigemId
        );
    }

    @GetMapping("/api/obras/{obraId}/pdoc/atual")
    public PdocResultadoResponse atual(@PathVariable String obraId) {
        return service.buscarAtual(obraId);
    }

    @GetMapping("/api/obras/{obraId}/pdoc/historico")
    public PdocHistoricoResponse historico(
            @PathVariable String obraId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.buscarHistorico(obraId, page, size);
    }
}

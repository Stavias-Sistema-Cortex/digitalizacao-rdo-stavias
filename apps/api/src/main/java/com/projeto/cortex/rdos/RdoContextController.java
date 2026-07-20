package com.projeto.cortex.rdos;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class RdoContextController {

    private final RdoContextService service;
    private final CurrentUserService currentUserService;

    public RdoContextController(
            RdoContextService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/rdos/contexto")
    public RdoContextResponse contexto(
            @RequestParam String obraId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate data
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.buscarContexto(obraId, data);
    }
}

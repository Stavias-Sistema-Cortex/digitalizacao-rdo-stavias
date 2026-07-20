package com.projeto.cortex.obras;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ObraImportController {

    private final ObraSeedImportService obraSeedImportService;
    private final CurrentUserService currentUserService;

    @Value("${cortex.import.enabled:false}")
    private boolean importEnabled;

    public ObraImportController(
            ObraSeedImportService obraSeedImportService,
            CurrentUserService currentUserService
    ) {
        this.obraSeedImportService = obraSeedImportService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/obras/import/seed")
    public ObraSeedImportResult importarSeed() {
        currentUserService.requireAdmin();
        if (!importEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Importação manual desabilitada. Defina CORTEX_IMPORT_ENABLED=true para habilitar."
            );
        }

        return obraSeedImportService.importarSeedPadrao();
    }
}

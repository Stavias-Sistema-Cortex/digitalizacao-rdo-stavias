package com.projeto.cortex.programacoes;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProgramacaoImportController {

    private final ProgramacaoSeedImportService programacaoSeedImportService;
    private final CurrentUserService currentUserService;

    @Value("${cortex.import.enabled:false}")
    private boolean importEnabled;

    public ProgramacaoImportController(
            ProgramacaoSeedImportService programacaoSeedImportService,
            CurrentUserService currentUserService
    ) {
        this.programacaoSeedImportService = programacaoSeedImportService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/programacoes/import/seed")
    public ProgramacaoSeedImportResult importarSeed() {
        currentUserService.requireAdmin();
        if (!importEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Importação manual desabilitada. Defina CORTEX_IMPORT_ENABLED=true para habilitar."
            );
        }

        return programacaoSeedImportService.importarSeedPadrao();
    }
}

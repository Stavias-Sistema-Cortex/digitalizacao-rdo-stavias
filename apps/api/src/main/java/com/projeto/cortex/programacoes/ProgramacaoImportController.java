package com.projeto.cortex.programacoes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProgramacaoImportController {

    private final ProgramacaoSeedImportService programacaoSeedImportService;

    @Value("${cortex.import.enabled:false}")
    private boolean importEnabled;

    public ProgramacaoImportController(ProgramacaoSeedImportService programacaoSeedImportService) {
        this.programacaoSeedImportService = programacaoSeedImportService;
    }

    @PostMapping("/api/programacoes/import/seed")
    public ProgramacaoSeedImportResult importarSeed() {
        if (!importEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Importação manual desabilitada. Defina CORTEX_IMPORT_ENABLED=true para habilitar."
            );
        }

        return programacaoSeedImportService.importarSeedPadrao();
    }
}

package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class ColaboradorController {

    private final ColaboradorService colaboradorService;
    private final ColaboradorImportService colaboradorImportService;
    private final CurrentUserService currentUserService;

    @Value("${cortex.import.enabled:false}")
    private boolean importEnabled;

    public ColaboradorController(
            ColaboradorService colaboradorService,
            ColaboradorImportService colaboradorImportService,
            CurrentUserService currentUserService
    ) {
        this.colaboradorService = colaboradorService;
        this.colaboradorImportService = colaboradorImportService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/colaboradores")
    public List<ColaboradorResponse> listarColaboradores(
            @RequestParam(required = false) String query
    ) {
        currentUserService.requireAdmin();
        return colaboradorService.listarColaboradores(query);
    }

    @PostMapping("/api/colaboradores/import/acad")
    public ColaboradorImportResult importarDaAcademy() {
        currentUserService.requireAdmin();
        if (!importEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Importação de colaboradores está desativada. Defina CORTEX_IMPORT_ENABLED=true para ativar."
            );
        }

        return colaboradorImportService.importarUsuariosDaAcademy();
    }
}

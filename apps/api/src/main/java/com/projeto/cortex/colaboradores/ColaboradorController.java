package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class ColaboradorController {

    private final ColaboradorService colaboradorService;
    private final ColaboradorImportService colaboradorImportService;
    private final CurrentUserService currentUserService;
    private final PapelAcessoService papelAcessoService;

    @Value("${cortex.import.enabled:false}")
    private boolean importEnabled;

    public ColaboradorController(
            ColaboradorService colaboradorService,
            ColaboradorImportService colaboradorImportService,
            CurrentUserService currentUserService,
            PapelAcessoService papelAcessoService
    ) {
        this.colaboradorService = colaboradorService;
        this.colaboradorImportService = colaboradorImportService;
        this.currentUserService = currentUserService;
        this.papelAcessoService = papelAcessoService;
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

    @PutMapping("/api/colaboradores/{id}/papel-acesso")
    public PapelAcessoUpdateResponse alterarPapelAcesso(
            @PathVariable String id,
            @RequestBody PapelAcessoUpdateRequest request
    ) {
        currentUserService.requireAlfa();
        return papelAcessoService.alterar(id, request);
    }
}

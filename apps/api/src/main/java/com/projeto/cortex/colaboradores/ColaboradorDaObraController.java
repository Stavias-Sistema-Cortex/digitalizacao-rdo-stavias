package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Colaboradores de uma obra, escopados por vínculo. Permite ao Beta carregar,
 * offline, os colaboradores da obra a que tem acesso — sem expor o catálogo
 * global de colaboradores (que é administrativo, em {@code /api/colaboradores}).
 */
@RestController
public class ColaboradorDaObraController {

    private final ColaboradorDaObraService service;
    private final CurrentUserService currentUserService;

    public ColaboradorDaObraController(
            ColaboradorDaObraService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/obras/{obraId}/colaboradores")
    public List<ColaboradorDaObraResponse> listar(@PathVariable String obraId) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.listarPorObra(obraId);
    }

    @GetMapping("/api/obras/{obraId}/colaboradores/autorizados")
    public ColaboradoresAutorizadosObraResponse listarAutorizados(
            @PathVariable String obraId
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.listarAutorizados(obraId);
    }
}

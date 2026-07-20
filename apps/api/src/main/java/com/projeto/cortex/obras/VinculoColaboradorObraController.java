package com.projeto.cortex.obras;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gestão dos vínculos colaborador ↔ obra. Exclusivo do papel Alfa: apenas o
 * escopo global pode atribuir ou revogar o acesso de colaboradores às obras.
 */
@RestController
public class VinculoColaboradorObraController {

    private final VinculoColaboradorObraService service;
    private final CurrentUserService currentUserService;

    public VinculoColaboradorObraController(
            VinculoColaboradorObraService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/obras/{obraId}/vinculos")
    public List<VinculoColaboradorObraResponse> listar(
            @PathVariable String obraId
    ) {
        currentUserService.requireAdmin();
        return service.listarPorObra(obraId);
    }

    @PostMapping("/api/obras/{obraId}/vinculos")
    public VinculoColaboradorObraResponse vincular(
            @PathVariable String obraId,
            @RequestBody VinculoColaboradorObraRequest request
    ) {
        currentUserService.requireAdmin();
        String atribuidoPor = currentUserService.requireUserId();
        return service.vincular(
                obraId,
                request == null ? null : request.colaboradorId(),
                request == null ? null : request.papelNaObra(),
                atribuidoPor
        );
    }

    @DeleteMapping("/api/obras/{obraId}/vinculos/{colaboradorId}")
    public VinculoColaboradorObraResponse revogar(
            @PathVariable String obraId,
            @PathVariable String colaboradorId
    ) {
        currentUserService.requireAdmin();
        String revogadoPor = currentUserService.requireUserId();
        return service.revogar(obraId, colaboradorId, revogadoPor);
    }
}

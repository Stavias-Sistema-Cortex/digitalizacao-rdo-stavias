package com.projeto.cortex.obras;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ObraController {

    private final ObraService obraService;
    private final CurrentUserService currentUserService;
    private final ObrasRelacionadasService obrasRelacionadasService;

    public ObraController(
            ObraService obraService,
            CurrentUserService currentUserService,
            ObrasRelacionadasService obrasRelacionadasService
    ) {
        this.obraService = obraService;
        this.currentUserService = currentUserService;
        this.obrasRelacionadasService = obrasRelacionadasService;
    }

    @GetMapping("/api/obras")
    public List<ObraResponse> listarObras(@RequestParam(required = false) String query) {
        currentUserService.requireAdmin();
        return obraService.listarObras(query);
    }

    @PostMapping("/api/obras")
    @ResponseStatus(HttpStatus.CREATED)
    public ObraResponse criarObra(@RequestBody ObraRequest request) {
        currentUserService.requireAdmin();
        return obraService.criarObra(
                request,
                currentUserService.requireUserId()
        );
    }

    @GetMapping("/api/obras/relacionadas")
    public List<ObraRelacionadaResponse> listarObrasRelacionadas() {
        return obrasRelacionadasService.listarParaColaborador();
    }
}

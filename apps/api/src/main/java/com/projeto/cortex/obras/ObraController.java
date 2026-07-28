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
        currentUserService.requireAlfa();
        return obraService.listarObras(query);
    }

    @PostMapping("/api/obras")
    @ResponseStatus(HttpStatus.CREATED)
    public ObraResponse criarObra(@RequestBody ObraRequest request) {
        currentUserService.requireAlfa();
        return obraService.criarObra(
                request,
                currentUserService.requireUserId()
        );
    }

    @PatchMapping("/api/obras/{obraId}")
    public ObraResponse atualizarObra(
            @PathVariable String obraId,
            @RequestBody ObraUpdateRequest request
    ) {
        currentUserService.requireAlfa();
        return obraService.atualizarObra(
                obraId,
                request,
                currentUserService.requireUserId()
        );
    }

    @PostMapping("/api/obras/{obraId}/desativar")
    public ObraResponse desativarObra(
            @PathVariable String obraId,
            @RequestBody ObraVersionRequest request
    ) {
        currentUserService.requireAlfa();
        return obraService.desativarObra(
                obraId,
                request,
                currentUserService.requireUserId()
        );
    }

    @PostMapping("/api/obras/{obraId}/arquivar")
    public ObraResponse arquivarObra(
            @PathVariable String obraId,
            @RequestBody ObraVersionRequest request
    ) {
        currentUserService.requireAlfa();
        return obraService.arquivarObra(
                obraId,
                request,
                currentUserService.requireUserId()
        );
    }

    @PostMapping("/api/obras/{obraId}/restaurar")
    public ObraResponse restaurarObra(
            @PathVariable String obraId,
            @RequestBody ObraVersionRequest request
    ) {
        currentUserService.requireAlfa();
        return obraService.restaurarObra(
                obraId,
                request,
                currentUserService.requireUserId()
        );
    }

    @GetMapping("/api/obras/arquivadas")
    public List<ObraResponse> listarObrasArquivadas(
            @RequestParam(required = false) String query
    ) {
        currentUserService.requireAlfa();
        return obraService.listarObrasArquivadas(
                query,
                currentUserService.requireUserId()
        );
    }

    @GetMapping("/api/obras/relacionadas")
    public List<ObraRelacionadaResponse> listarObrasRelacionadas() {
        return obrasRelacionadasService.listarParaColaborador();
    }
}

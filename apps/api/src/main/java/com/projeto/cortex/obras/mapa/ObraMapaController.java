package com.projeto.cortex.obras.mapa;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/obras/{obraId}")
public class ObraMapaController {

    private final ObraMapaService service;
    private final CurrentUserService currentUserService;

    public ObraMapaController(
            ObraMapaService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/mapa")
    public ObraMapaResponse buscarMapa(@PathVariable String obraId) {
        return service.buscarMapa(obraId);
    }

    @PostMapping("/geometrias")
    @ResponseStatus(HttpStatus.CREATED)
    public ObraGeometriaResponse criar(
            @PathVariable String obraId,
            @RequestBody ObraGeometriaRequest request
    ) {
        currentUserService.requireAlfa();
        return service.criar(obraId, request);
    }

    @PostMapping("/geometrias/campo")
    @ResponseStatus(HttpStatus.CREATED)
    public ObraGeometriaResponse registrarCapturaCampo(
            @PathVariable String obraId,
            @RequestBody ObraGeometriaRequest request
    ) {
        return service.registrarCapturaCampo(obraId, request);
    }

    @PutMapping("/geometrias/{featureId}")
    public ObraGeometriaResponse atualizar(
            @PathVariable String obraId,
            @PathVariable String featureId,
            @RequestBody ObraGeometriaRequest request
    ) {
        currentUserService.requireAlfa();
        return service.atualizar(obraId, featureId, request);
    }

    /**
     * Silencia uma linha derivada do RDO — a lixeira que não apaga o RDO.
     *
     * <p>O identificador vai no corpo porque carrega dois-pontos, e rota é
     * lugar de identidade simples. Só Alfa, como todo encerramento no mapa.
     */
    @PostMapping("/geometrias/derivadas/silenciar")
    public void silenciarDerivada(
            @PathVariable String obraId,
            @RequestBody SilenciarDerivadaRequest request
    ) {
        service.silenciarDerivada(
                obraId,
                request == null ? null : request.featureId(),
                request == null ? null : request.motivo()
        );
    }

    /** O pedido da lixeira da linha derivada. */
    public record SilenciarDerivadaRequest(String featureId, String motivo) {
    }

    @PostMapping("/geometrias/{featureId}/encerrar")
    public ObraGeometriaResponse encerrar(
            @PathVariable String obraId,
            @PathVariable String featureId,
            @RequestBody ObraGeometriaEndRequest request
    ) {
        currentUserService.requireAlfa();
        return service.encerrar(obraId, featureId, request);
    }
}

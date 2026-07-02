package com.projeto.cortex.importacao;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class RdoImportacaoController {

    private final RdoImportacaoHistoricaService service;
    private final CurrentUserService currentUserService;

    public RdoImportacaoController(
            RdoImportacaoHistoricaService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @PostMapping(
            value = "/api/rdos/importacoes",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public RdoImportacaoResponse analisar(
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestPart(value = "usuarioId", required = false) String usuarioId
    ) {
        currentUserService.requireAdmin();
        return service.analisar(arquivo, currentUserService.requireUserId());
    }

    @GetMapping("/api/rdos/importacoes/{id}")
    public RdoImportacaoResponse buscar(@PathVariable String id) {
        String currentUserId = currentUserService.requireUserId();
        currentUserService.requireAdmin();
        return service.buscar(
                id,
                currentUserId,
                true
        );
    }

    @PostMapping("/api/rdos/importacoes/{id}/simular")
    public RdoImportacaoResponse simular(
            @PathVariable String id,
            @RequestBody(required = false) RdoImportacaoConfirmRequest request
    ) {
        currentUserService.requireAdmin();
        RdoImportacaoConfirmRequest dryRunRequest =
                new RdoImportacaoConfirmRequest(
                        request == null ? null : request.estrategiaDuplicidade(),
                        currentUserService.requireUserId(),
                        true
                );

        return service.confirmar(
                id,
                dryRunRequest,
                true
        );
    }

    @PostMapping("/api/rdos/importacoes/{id}/confirmar")
    public RdoImportacaoResponse confirmar(
            @PathVariable String id,
            @RequestBody(required = false) RdoImportacaoConfirmRequest request
    ) {
        String currentUserId = currentUserService.requireUserId();
        currentUserService.requireAdmin();
        return service.confirmar(
                id,
                request == null
                        ? new RdoImportacaoConfirmRequest(null, currentUserId, false)
                        : new RdoImportacaoConfirmRequest(
                                request.estrategiaDuplicidade(),
                                currentUserId,
                                false
                        ),
                true
        );
    }
}

package com.projeto.cortex.importacao;

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

    public RdoImportacaoController(RdoImportacaoHistoricaService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/api/rdos/importacoes",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public RdoImportacaoResponse analisar(
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestPart(value = "usuarioId", required = false) String usuarioId
    ) {
        return service.analisar(arquivo, usuarioId);
    }

    @GetMapping("/api/rdos/importacoes/{id}")
    public RdoImportacaoResponse buscar(@PathVariable String id) {
        return service.buscar(id);
    }

    @PostMapping("/api/rdos/importacoes/{id}/simular")
    public RdoImportacaoResponse simular(
            @PathVariable String id,
            @RequestBody(required = false) RdoImportacaoConfirmRequest request
    ) {
        RdoImportacaoConfirmRequest dryRunRequest =
                new RdoImportacaoConfirmRequest(
                        request == null ? null : request.estrategiaDuplicidade(),
                        request == null ? null : request.usuarioId(),
                        true
                );

        return service.confirmar(id, dryRunRequest);
    }

    @PostMapping("/api/rdos/importacoes/{id}/confirmar")
    public RdoImportacaoResponse confirmar(
            @PathVariable String id,
            @RequestBody(required = false) RdoImportacaoConfirmRequest request
    ) {
        return service.confirmar(
                id,
                request == null
                        ? new RdoImportacaoConfirmRequest(null, null, false)
                        : request
        );
    }
}

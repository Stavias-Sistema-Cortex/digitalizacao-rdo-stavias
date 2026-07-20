package com.projeto.cortex.intelligence.stavia.contextdocs;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class StaviaContextDocumentController {

    private final StaviaContextDocumentService service;
    private final CurrentUserService currentUserService;

    public StaviaContextDocumentController(
            StaviaContextDocumentService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @PostMapping(
            value = "/api/stavia/obras/{obraId}/contextos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public StaviaContextDocumentResponse adicionar(
            @PathVariable String obraId,
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestPart(value = "usuarioId", required = false)
            String usuarioId,
            @RequestPart(value = "descricao", required = false)
            String descricao
    ) {
        currentUserService.requireAdmin();
        return service.adicionar(
                obraId,
                arquivo,
                currentUserService.requireUserId(),
                descricao
        );
    }

    @GetMapping("/api/stavia/obras/{obraId}/contextos")
    public List<StaviaContextDocumentResponse> listar(
            @PathVariable String obraId
    ) {
        currentUserService.requireAdmin();
        return service.listar(obraId);
    }
}

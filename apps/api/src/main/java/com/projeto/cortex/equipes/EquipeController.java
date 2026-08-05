package com.projeto.cortex.equipes;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/equipes")
public class EquipeController {

    private final EquipeService service;
    private final EquipeHistoryService historyService;
    private final CurrentUserService currentUserService;

    public EquipeController(
            EquipeService service,
            EquipeHistoryService historyService,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.historyService = historyService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public EquipePageResponse listar(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String texto,
            @RequestParam(required = false) String funcaoOperacionalId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDateTime vigenteEm,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return service.listar(new EquipeFilter(
                obraId,
                texto,
                funcaoOperacionalId,
                status,
                vigenteEm,
                page,
                size
        ));
    }

    @GetMapping("/{id}")
    public EquipeResponse buscar(@PathVariable String id) {
        return service.buscarPorId(id);
    }

    @GetMapping("/{id}/historico")
    public EquipeHistoryPageResponse historico(
            @PathVariable String id,
            @RequestParam(required = false) Long afterCommitSeq,
            @RequestParam(required = false) Integer limit
    ) {
        currentUserService.requireAlfa();
        return historyService.listar(id, afterCommitSeq, limit);
    }

    @GetMapping("/{id}/obras")
    public List<EquipeWorksiteResponse> listarObras(@PathVariable String id) {
        return service.listarObras(id);
    }

    @PostMapping
    public EquipeResponse criar(@RequestBody EquipeCreateRequest request) {
        currentUserService.requireAlfa();
        return service.criar(request);
    }

    @PutMapping("/{id}")
    public EquipeResponse atualizar(
            @PathVariable String id,
            @RequestBody EquipeUpdateRequest request
    ) {
        currentUserService.requireAlfa();
        return service.atualizar(id, request);
    }

    @PostMapping("/{id}/arquivar")
    public EquipeResponse arquivar(
            @PathVariable String id,
            @RequestBody EquipeArchiveRequest request
    ) {
        currentUserService.requireAlfa();
        return service.arquivar(
                id,
                request == null ? null : request.baseVersao(),
                request == null ? null : request.motivo(),
                request == null ? null : request.arquivadaEm()
        );
    }

    @PostMapping("/{id}/desarquivar")
    public EquipeResponse desarquivar(
            @PathVariable String id,
            @RequestBody EquipeUnarchiveRequest request
    ) {
        currentUserService.requireAlfa();
        return service.desarquivar(
                id,
                request == null ? null : request.baseVersao(),
                request == null ? null : request.desarquivadaEm()
        );
    }

    @PostMapping("/{id}/membros")
    public EquipeMemberResponse adicionarMembro(
            @PathVariable String id,
            @RequestBody EquipeMemberRequest request
    ) {
        currentUserService.requireAlfa();
        return service.adicionarMembro(id, request);
    }

    @PostMapping("/{id}/obras")
    public EquipeWorksiteResponse adicionarObra(
            @PathVariable String id,
            @RequestBody EquipeWorksiteRequest request
    ) {
        currentUserService.requireAlfa();
        return service.adicionarObra(id, request);
    }

    @PostMapping("/{id}/obras/{associacaoId}/encerrar")
    public EquipeWorksiteResponse encerrarObra(
            @PathVariable String id,
            @PathVariable String associacaoId,
            @RequestBody EquipeWorksiteEndRequest request
    ) {
        currentUserService.requireAlfa();
        return service.encerrarObra(
                id,
                associacaoId,
                request == null ? null : request.baseVersao(),
                request == null ? null : request.motivo(),
                request == null ? null : request.encerradoEm()
        );
    }

    @PutMapping("/{id}/membros/{participacaoId}")
    public EquipeMemberResponse atualizarMembro(
            @PathVariable String id,
            @PathVariable String participacaoId,
            @RequestBody EquipeMemberRequest request
    ) {
        currentUserService.requireAlfa();
        return service.atualizarMembro(id, participacaoId, request);
    }

    @PostMapping("/{id}/membros/{participacaoId}/encerrar")
    public EquipeMemberResponse encerrarMembro(
            @PathVariable String id,
            @PathVariable String participacaoId,
            @RequestBody EquipeMemberEndRequest request
    ) {
        currentUserService.requireAlfa();
        return service.encerrarMembro(
                id,
                participacaoId,
                request == null ? null : request.baseVersao(),
                request == null ? null : request.motivo(),
                request == null ? null : request.encerradoEm()
        );
    }
}

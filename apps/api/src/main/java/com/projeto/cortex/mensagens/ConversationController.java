package com.projeto.cortex.mensagens;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/api/conversas")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping
    public ConversaPageResponse listar(
            @RequestParam(required = false) String texto,
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String equipeId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return service.listar(new ConversaFilter(
                texto,
                obraId,
                equipeId,
                page,
                size
        ));
    }

    @GetMapping("/{id}")
    public ConversaResponse buscar(@PathVariable String id) {
        return service.buscarPorId(id);
    }

    @PostMapping
    public ConversaResponse criar(@RequestBody ConversaCreateRequest request) {
        return service.criar(request);
    }

    @PutMapping("/{id}")
    public ConversaResponse atualizar(
            @PathVariable String id,
            @RequestBody ConversaUpdateRequest request
    ) {
        return service.atualizar(id, request);
    }

    @PostMapping("/{id}/participantes")
    public ConversaParticipantResponse adicionarParticipante(
            @PathVariable String id,
            @RequestBody ConversaParticipantRequest request
    ) {
        return service.adicionarParticipante(id, request);
    }

    @PostMapping("/{id}/participantes/{participantId}/encerrar")
    public ConversaParticipantResponse encerrarParticipante(
            @PathVariable String id,
            @PathVariable String participantId,
            @RequestBody ConversaParticipantEndRequest request
    ) {
        return service.encerrarParticipante(id, participantId, request);
    }
}

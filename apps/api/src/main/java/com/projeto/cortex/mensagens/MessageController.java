package com.projeto.cortex.mensagens;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api")
public class MessageController {

    private final MessageService service;

    public MessageController(MessageService service) {
        this.service = service;
    }

    @GetMapping("/conversas/{conversationId}/mensagens")
    public MensagemPageResponse listar(
            @PathVariable String conversationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant antesDeClienteEm,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant antesDeServidorEm,
            @RequestParam(required = false) String antesDeId,
            @RequestParam(required = false) Integer limit
    ) {
        MensagemCursor cursor = antesDeClienteEm == null
                && antesDeServidorEm == null
                && antesDeId == null
                ? null
                : new MensagemCursor(
                        antesDeClienteEm,
                        antesDeServidorEm,
                        antesDeId
                );
        return service.listar(conversationId, cursor, limit);
    }

    @PostMapping("/conversas/{conversationId}/mensagens")
    public MensagemResponse enviar(
            @PathVariable String conversationId,
            @RequestBody MensagemCreateRequest request
    ) {
        return service.enviar(new MensagemCreateRequest(
                request.id(),
                conversationId,
                request.clientMessageId(),
                request.texto(),
                request.enviadaClienteEm(),
                request.referencias(),
                request.anexos()
        ));
    }

    @GetMapping("/mensagens/{messageId}")
    public MensagemResponse buscarPorId(@PathVariable String messageId) {
        return service.buscarPorId(messageId);
    }

    @PostMapping("/mensagens/{messageId}/recibos/entrega")
    public MensagemReciboResponse marcarEntregue(
            @PathVariable String messageId
    ) {
        return service.marcarEntregue(messageId);
    }

    @PostMapping("/mensagens/{messageId}/recibos/leitura")
    public MensagemReciboResponse marcarLida(@PathVariable String messageId) {
        return service.marcarLida(messageId);
    }
}

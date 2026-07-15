package com.projeto.cortex.mensagens.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.mensagens.api.AttachmentResponse;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingAuditContext;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MessageAttachmentSyncOperationHandler
        implements SyncOperationHandler {

    private static final String OPERATION = "ADICIONAR_MENSAGEM_ANEXO";

    private final MensagemService service;
    private final ObjectMapper objectMapper;

    public MessageAttachmentSyncOperationHandler(
            MensagemService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityType() {
        return "MENSAGEM_ANEXO";
    }

    @Override
    public Set<String> operations() {
        return Set.of(OPERATION);
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return false;
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        if (!OPERATION.equals(mutation.operacao())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação de anexo não suportada."
            );
        }
        AttachmentPayload payload = value(mutation.payload());
        AttachmentResponse response = service.addAttachment(
                payload.mensagemId(),
                mutation.entidadeId(),
                payload.objetoId(),
                payload.sha256(),
                new MessagingAuditContext(
                        context.actorId(),
                        context.deviceId(),
                        mutation.correlacaoId(),
                        "OFFLINE"
                )
        );
        return new AppliedSyncMutation(
                entityType(),
                response.id(),
                objectMapper.valueToTree(response)
        );
    }

    private AttachmentPayload value(JsonNode payload) {
        try {
            if (payload == null || payload.isNull()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "payload é obrigatório."
                );
            }
            return objectMapper.treeToValue(payload, AttachmentPayload.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "payload de anexo inválido."
            );
        }
    }

    private record AttachmentPayload(
            String mensagemId,
            String objetoId,
            String sha256
    ) {
    }
}

package com.projeto.cortex.mensagens.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.mensagens.api.MessageCreateRequest;
import com.projeto.cortex.mensagens.api.AttachmentReferenceRequest;
import com.projeto.cortex.mensagens.api.MessageEditRequest;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingAuditContext;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MessageSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_MENSAGEM",
            "EDITAR_MENSAGEM",
            "EXCLUIR_MENSAGEM"
    );

    private final MensagemService service;
    private final ObjectMapper objectMapper;

    public MessageSyncOperationHandler(
            MensagemService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityType() {
        return "MENSAGEM";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_MENSAGEM".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        MessagingAuditContext audit = new MessagingAuditContext(
                context.actorId(),
                context.deviceId(),
                mutation.correlacaoId(),
                "OFFLINE"
        );
        MessageResponse response = switch (mutation.operacao()) {
            case "CRIAR_MENSAGEM" -> create(mutation, audit);
            case "EDITAR_MENSAGEM" -> service.edit(
                    requireEntityId(mutation),
                    value(mutation.payload(), MessageEditRequest.class),
                    audit
            );
            case "EXCLUIR_MENSAGEM" -> service.delete(
                    requireEntityId(mutation),
                    audit
            );
            default -> throw unsupported();
        };
        return new AppliedSyncMutation(
                entityType(),
                response.id(),
                objectMapper.valueToTree(response)
        );
    }

    private MessageResponse create(
            SyncPushRequest.MutacaoCliente mutation,
            MessagingAuditContext audit
    ) {
        CreateMessagePayload payload = value(
                mutation.payload(),
                CreateMessagePayload.class
        );
        return service.send(
                payload.conversaId(),
                new MessageCreateRequest(
                        mutation.entidadeId(),
                        payload.corpo(),
                        mutation.clientMutationId(),
                        payload.criadaNoClienteEm() == null
                                ? mutation.criadaNoClienteEm()
                                : payload.criadaNoClienteEm(),
                        payload.anexos()
                ),
                audit
        );
    }

    private String requireEntityId(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.entidadeId() == null || mutation.entidadeId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeId da mensagem é obrigatório."
            );
        }
        return mutation.entidadeId();
    }

    private <T> T value(JsonNode payload, Class<T> type) {
        try {
            if (payload == null || payload.isNull()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "payload é obrigatório."
                );
            }
            return objectMapper.treeToValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "payload de mensagem inválido."
            );
        }
    }

    private ResponseStatusException unsupported() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Operação de mensagem não suportada."
        );
    }

    private record CreateMessagePayload(
            String conversaId,
            String corpo,
            LocalDateTime criadaNoClienteEm,
            List<AttachmentReferenceRequest> anexos
    ) {
    }
}

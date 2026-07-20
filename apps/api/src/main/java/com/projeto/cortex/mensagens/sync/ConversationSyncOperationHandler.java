package com.projeto.cortex.mensagens.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.mensagens.api.ConversationCreateRequest;
import com.projeto.cortex.mensagens.api.ConversationResponse;
import com.projeto.cortex.mensagens.api.ParticipantChangeRequest;
import com.projeto.cortex.mensagens.domain.ConversaService;
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
public class ConversationSyncOperationHandler
        implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_CONVERSA",
            "ADICIONAR_PARTICIPANTE_CONVERSA",
            "REMOVER_PARTICIPANTE_CONVERSA"
    );

    private final ConversaService service;
    private final ObjectMapper objectMapper;

    public ConversationSyncOperationHandler(
            ConversaService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityType() {
        return "CONVERSA";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_CONVERSA".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        MessagingAuditContext audit = audit(mutation, context);
        ConversationResponse response = switch (mutation.operacao()) {
            case "CRIAR_CONVERSA" -> create(mutation, audit);
            case "ADICIONAR_PARTICIPANTE_CONVERSA" -> {
                service.addParticipant(
                        requireEntityId(mutation),
                        value(mutation.payload(), ParticipantChangeRequest.class),
                        audit
                );
                yield service.get(requireEntityId(mutation));
            }
            case "REMOVER_PARTICIPANTE_CONVERSA" -> {
                ParticipantChangeRequest request = value(
                        mutation.payload(),
                        ParticipantChangeRequest.class
                );
                service.removeParticipant(
                        requireEntityId(mutation),
                        request.colaboradorId(),
                        audit
                );
                yield service.get(requireEntityId(mutation));
            }
            default -> throw unsupported();
        };
        return new AppliedSyncMutation(
                entityType(),
                response.id(),
                objectMapper.valueToTree(response)
        );
    }

    private ConversationResponse create(
            SyncPushRequest.MutacaoCliente mutation,
            MessagingAuditContext audit
    ) {
        ConversationCreateRequest request = value(
                mutation.payload(),
                ConversationCreateRequest.class
        );
        if ((request.id() == null || request.id().isBlank())
                && mutation.entidadeId() != null
                && !mutation.entidadeId().isBlank()) {
            request = new ConversationCreateRequest(
                    mutation.entidadeId(),
                    request.tipo(),
                    request.titulo(),
                    request.obraId(),
                    request.equipeId(),
                    request.participanteIds()
            );
        }
        return service.create(request, audit);
    }

    private MessagingAuditContext audit(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        return new MessagingAuditContext(
                context.actorId(),
                context.deviceId(),
                mutation.correlacaoId(),
                "OFFLINE"
        );
    }

    private String requireEntityId(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation.entidadeId() == null || mutation.entidadeId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeId da conversa é obrigatório."
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
                    "payload de conversa inválido."
            );
        }
    }

    private ResponseStatusException unsupported() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Operação de conversa não suportada."
        );
    }
}

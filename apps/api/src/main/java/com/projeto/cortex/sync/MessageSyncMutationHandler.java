package com.projeto.cortex.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.mensagens.MensagemCreateRequest;
import com.projeto.cortex.mensagens.MensagemResponse;
import com.projeto.cortex.mensagens.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
public final class MessageSyncMutationHandler implements SyncMutationHandler {

    private static final String ENTITY_TYPE = "MENSAGEM";
    private static final String CREATE_OPERATION = "CRIAR_MENSAGEM";
    private static final Set<String> OPERATIONS = Set.of(CREATE_OPERATION);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MessageService messageService;

    public MessageSyncMutationHandler(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MessageService messageService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.messageService = messageService;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return false;
    }

    @Override
    public SyncMutationApplied apply(SyncPushRequest.MutacaoCliente mutation) {
        requireMessageCreate(mutation);
        MensagemCreateRequest request = toRequest(mutation.payload());
        String stableId = requireText(request.id(), "id");
        String entityId = requireText(mutation.entidadeId(), "entidadeId");
        if (!entityId.equals(stableId)) {
            throw badRequest("entidadeId não corresponde ao ID informado no payload.");
        }
        requireText(request.clientMessageId(), "clientMessageId");

        MensagemResponse response = messageService.enviar(request);
        long entityVersion = currentEntityVersion(response.id());
        long commitSeq = currentCommitSeq(response.id());
        ObjectNode canonical = objectMapper.valueToTree(response);
        canonical.put("versaoEntidade", entityVersion);

        return new SyncMutationApplied(
                ENTITY_TYPE,
                response.id(),
                entityVersion,
                commitSeq,
                canonical
        );
    }

    private void requireMessageCreate(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation == null || !CREATE_OPERATION.equals(mutation.operacao())) {
            throw badRequest("Operação de mensagem não suportada.");
        }
        if (!ENTITY_TYPE.equals(mutation.entidadeTipo())) {
            throw badRequest(
                    "entidadeTipo precisa ser MENSAGEM para CRIAR_MENSAGEM."
            );
        }
    }

    private MensagemCreateRequest toRequest(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw badRequest("payload deve ser um objeto.");
        }
        try {
            return objectMapper.treeToValue(payload, MensagemCreateRequest.class);
        } catch (JsonProcessingException exception) {
            throw badRequest("payload inválido para criação de mensagem.");
        }
    }

    private long currentEntityVersion(String messageId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT versao_entidade
                FROM cortex_estado_entidade
                WHERE tipo_entidade = ?
                  AND entidade_id = ?
                """,
                Long.class,
                ENTITY_TYPE,
                messageId
        );
        if (version == null || version <= 0) {
            throw new IllegalStateException(
                    "Mensagem aplicada sem versão autoritativa para sincronização."
            );
        }
        return version;
    }

    private long currentCommitSeq(String messageId) {
        Long commitSeq = jdbcTemplate.queryForObject(
                """
                SELECT ev.commit_seq
                FROM cortex_estado_entidade state
                JOIN cortex_evento_operacional ev
                    ON ev.sequencia = state.ultimo_evento_seq
                WHERE state.tipo_entidade = ?
                  AND state.entidade_id = ?
                """,
                Long.class,
                ENTITY_TYPE,
                messageId
        );
        if (commitSeq == null || commitSeq <= 0) {
            throw new IllegalStateException(
                    "Mensagem aplicada sem commit autoritativo para sincronização."
            );
        }
        return commitSeq;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(
                    field + " é obrigatório para criação offline idempotente."
            );
        }
        return value.trim();
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}

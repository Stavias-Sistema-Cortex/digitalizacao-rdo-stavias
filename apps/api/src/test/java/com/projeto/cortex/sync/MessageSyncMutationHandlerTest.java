package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.mensagens.MensagemCreateRequest;
import com.projeto.cortex.mensagens.MensagemResponse;
import com.projeto.cortex.mensagens.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageSyncMutationHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MessageService messageService = mock(MessageService.class);
    private final MessageSyncMutationHandler handler = new MessageSyncMutationHandler(
            jdbcTemplate,
            objectMapper,
            messageService
    );

    @Test
    void ownsMessageCreateWithoutRequiringBaseVersion() {
        assertThat(handler.operations()).isEqualTo(Set.of("CRIAR_MENSAGEM"));
        assertThat(handler.requiresBaseVersion("CRIAR_MENSAGEM")).isFalse();
    }

    @Test
    void delegatesOfflineCreateAndReturnsCanonicalPayloadVersionAndCommit() {
        MensagemCreateRequest request = request();
        MensagemResponse response = response();
        when(messageService.enviar(request)).thenReturn(response);
        stubCanonicalCursor(3L, 209L);

        SyncMutationApplied applied = handler.apply(mutation(
                "MENSAGEM",
                "message-1",
                objectMapper.valueToTree(request)
        ));

        assertThat(applied.entityType()).isEqualTo("MENSAGEM");
        assertThat(applied.entityId()).isEqualTo("message-1");
        assertThat(applied.entityVersion()).isEqualTo(3);
        assertThat(applied.commitSeq()).isEqualTo(209);
        assertThat(applied.result().path("id").asText()).isEqualTo("message-1");
        assertThat(applied.result().path("clientMessageId").asText())
                .isEqualTo("client-message-1");
        assertThat(applied.result().path("versaoEntidade").asLong()).isEqualTo(3);
        assertThat(applied.result().path("enviadaClienteEm").asText())
                .isEqualTo("2026-07-15T13:49:00Z");
        verify(messageService).enviar(request);
    }

    @Test
    void rejectsWrongEntityTypeBeforeCallingDomainService() {
        assertThatThrownBy(() -> handler.apply(mutation(
                "CONVERSA",
                "message-1",
                objectMapper.valueToTree(request())
        )))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );

        verify(messageService, never()).enviar(request());
    }

    @Test
    void requiresStableMatchingIdAndClientMessageId() {
        ObjectNode missingClientId = objectMapper.valueToTree(request());
        missingClientId.remove("clientMessageId");

        assertThatThrownBy(() -> handler.apply(mutation(
                "MENSAGEM",
                "message-1",
                missingClientId
        )))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("clientMessageId");
                });

        assertThatThrownBy(() -> handler.apply(mutation(
                "MENSAGEM",
                "another-message",
                objectMapper.valueToTree(request())
        )))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("entidadeId");
                });

        verify(messageService, never()).enviar(request());
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String entityType,
            String entityId,
            ObjectNode payload
    ) {
        return new SyncPushRequest.MutacaoCliente(
                "mutation-1",
                entityType,
                entityId,
                "CRIAR_MENSAGEM",
                null,
                payload,
                LocalDateTime.of(2026, 7, 15, 10, 49)
        );
    }

    private MensagemCreateRequest request() {
        return new MensagemCreateRequest(
                "message-1",
                "conversation-1",
                "client-message-1",
                "Frente liberada",
                Instant.parse("2026-07-15T13:49:00Z"),
                List.of(),
                List.of()
        );
    }

    private MensagemResponse response() {
        return new MensagemResponse(
                "message-1",
                "conversation-1",
                "user-1",
                "Usuário",
                "client-message-1",
                "Frente liberada",
                "ENVIADA",
                Instant.parse("2026-07-15T13:49:00Z"),
                Instant.parse("2026-07-15T13:49:01Z"),
                Instant.parse("2026-07-15T13:49:01Z"),
                3,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private void stubCanonicalCursor(long version, long commitSeq) {
        when(jdbcTemplate.queryForObject(
                contains("SELECT versao_entidade"),
                eq(Long.class),
                eq("MENSAGEM"),
                eq("message-1")
        )).thenReturn(version);
        when(jdbcTemplate.queryForObject(
                contains("SELECT ev.commit_seq"),
                eq(Long.class),
                eq("MENSAGEM"),
                eq("message-1")
        )).thenReturn(commitSeq);
    }
}

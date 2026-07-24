package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class TarefaSyncOperationHandlerTest {

    private static final String WORKSITE =
            "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR =
            "20000000-0000-4000-8000-000000000002";
    private static final String DEVICE =
            "30000000-0000-4000-8000-000000000003";
    private static final String TASK =
            "40000000-0000-4000-8000-000000000004";
    private static final String MUTATION =
            "50000000-0000-4000-8000-000000000005";
    private static final String OCCURRED_AT =
            "2026-07-23T12:00:00.000Z";

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void createsTaskWithEnvelopeIdentityAuthorizedActorAndOntologyEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TarefaSyncOperationHandler handler = new TarefaSyncOperationHandler(
                jdbc, mapper, memory, currentUser
        );

        AppliedSyncMutation applied = handler.apply(
                mutation("CRIAR_TAREFA", "CREATE", null, createPayload()),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        verify(currentUser).requireWorksiteAccess(WORKSITE);
        verify(jdbc).update(
                contains("INSERT INTO tarefa"),
                any(Object[].class)
        );
        verify(memory).registrarEvento(
                eq("TAREFA"),
                eq(TASK),
                eq("TAREFA_CRIADA"),
                eq("SYNC"),
                eq(WORKSITE),
                any()
        );
        assertThat(applied.entityType()).isEqualTo("TAREFA");
        assertThat(applied.entityId()).isEqualTo(TASK);
        assertThat(applied.result().path("titulo").asText())
                .isEqualTo("Sinalizar desvio");
        assertThat(handler.requiresBaseVersion("CRIAR_TAREFA")).isFalse();
    }

    @Test
    void completesReopensAndDeletesOnlyInsideEnvelopeWorksite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TarefaSyncOperationHandler handler = new TarefaSyncOperationHandler(
                jdbc, mapper, memory, currentUser
        );

        handler.apply(
                mutation(
                        "CONCLUIR_TAREFA",
                        "TRANSITION",
                        1L,
                        transitionPayload(true, OCCURRED_AT, null)
                ),
                new SyncMutationContext(ACTOR, DEVICE)
        );
        handler.apply(
                mutation(
                        "REABRIR_TAREFA",
                        "TRANSITION",
                        2L,
                        transitionPayload(false, null, null)
                ),
                new SyncMutationContext(ACTOR, DEVICE)
        );
        handler.apply(
                mutation(
                        "EXCLUIR_TAREFA",
                        "DELETE",
                        3L,
                        transitionPayload(false, null, OCCURRED_AT)
                ),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        verify(currentUser, org.mockito.Mockito.times(3))
                .requireWorksiteAccess(WORKSITE);
        verify(jdbc).update(
                contains("SET concluida = TRUE"),
                any(Object[].class)
        );
        verify(jdbc).update(
                contains("SET concluida = FALSE"),
                any(Object[].class)
        );
        verify(jdbc).update(
                contains("SET deletada_em = ?"),
                any(Object[].class)
        );
        verify(memory).registrarEvento(
                eq("TAREFA"), eq(TASK), eq("TAREFA_CONCLUIDA"),
                eq("SYNC"), eq(WORKSITE), any()
        );
        verify(memory).registrarEvento(
                eq("TAREFA"), eq(TASK), eq("TAREFA_REABERTA"),
                eq("SYNC"), eq(WORKSITE), any()
        );
        verify(memory).registrarEvento(
                eq("TAREFA"), eq(TASK), eq("TAREFA_EXCLUIDA"),
                eq("SYNC"), eq(WORKSITE), any()
        );
        assertThat(handler.requiresBaseVersion("CONCLUIR_TAREFA")).isTrue();
        assertThat(handler.requiresBaseVersion("EXCLUIR_TAREFA")).isTrue();
    }

    @Test
    void updatesEditableFieldsWithoutSmugglingACompletionTransition() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TarefaSyncOperationHandler handler = new TarefaSyncOperationHandler(
                jdbc, mapper, memory, currentUser
        );
        ObjectNode payload = transitionPayload(false, null, null);
        payload.put("titulo", "Sinalizar desvio atualizado");
        payload.put("prioridade", 3);

        AppliedSyncMutation applied = handler.apply(
                mutation("ATUALIZAR_TAREFA", "UPDATE", 1L, payload),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        verify(currentUser).requireWorksiteAccess(WORKSITE);
        verify(jdbc).update(
                contains("SET equipe = ?"),
                any(Object[].class)
        );
        verify(memory).registrarEvento(
                eq("TAREFA"), eq(TASK), eq("TAREFA_ATUALIZADA"),
                eq("SYNC"), eq(WORKSITE), any()
        );
        assertThat(applied.result().path("concluida").asBoolean()).isFalse();
        assertThat(handler.requiresBaseVersion("ATUALIZAR_TAREFA")).isTrue();
    }

    @Test
    void rejectsPayloadIdentityOrWorksiteMismatchBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        TarefaSyncOperationHandler handler = new TarefaSyncOperationHandler(
                jdbc, mapper, memory, currentUser
        );
        ObjectNode wrongIdentity = createPayload();
        wrongIdentity.put(
                "id",
                "60000000-0000-4000-8000-000000000006"
        );
        ObjectNode wrongWorksite = createPayload();
        wrongWorksite.put(
                "obraId",
                "70000000-0000-4000-8000-000000000007"
        );

        assertThatThrownBy(() -> handler.apply(
                mutation("CRIAR_TAREFA", "CREATE", null, wrongIdentity),
                new SyncMutationContext(ACTOR, DEVICE)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payload.id");
        assertThatThrownBy(() -> handler.apply(
                mutation("CRIAR_TAREFA", "CREATE", null, wrongWorksite),
                new SyncMutationContext(ACTOR, DEVICE)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payload.obraId");

        verify(currentUser, never()).requireWorksiteAccess(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(memory, never()).registrarEvento(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any()
        );
    }

    @Test
    void rejectsUnauthorizedWorksiteBeforeDomainOrOntologyWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Obra fora do escopo."
        )).when(currentUser).requireWorksiteAccess(WORKSITE);
        TarefaSyncOperationHandler handler = new TarefaSyncOperationHandler(
                jdbc, mapper, memory, currentUser
        );

        assertThatThrownBy(() -> handler.apply(
                mutation("CRIAR_TAREFA", "CREATE", null, createPayload()),
                new SyncMutationContext(ACTOR, DEVICE)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fora do escopo");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(memory, never()).registrarEvento(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any()
        );
    }

    @Test
    void rejectsTransitionWhenPersistedTaskStateDoesNotAllowIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        TarefaSyncOperationHandler handler = new TarefaSyncOperationHandler(
                jdbc,
                mapper,
                mock(CortexOperationalMemoryService.class),
                mock(CurrentUserService.class)
        );

        assertThatThrownBy(() -> handler.apply(
                mutation(
                        "CONCLUIR_TAREFA",
                        "TRANSITION",
                        1L,
                        transitionPayload(true, OCCURRED_AT, null)
                ),
                new SyncMutationContext(ACTOR, DEVICE)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("estado");
    }

    private ObjectNode createPayload() {
        ObjectNode payload = transitionPayload(false, null, null);
        payload.put("createdAt", OCCURRED_AT);
        return payload;
    }

    private ObjectNode transitionPayload(
            boolean completed,
            String completedAt,
            String deletedAt
    ) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", TASK);
        payload.put("obraId", WORKSITE);
        payload.put("equipe", "Fresagem");
        payload.put("titulo", "Sinalizar desvio");
        payload.put("observacoes", "Antes do início do turno.");
        payload.put("criadaPor", "Encarregada de campo");
        payload.put("criadaPorColaboradorId", ACTOR);
        payload.put("responsavelEquipe", "Equipe Fresagem");
        payload.putNull("responsavelColaboradorId");
        payload.put("prioridade", 1);
        payload.put("concluida", completed);
        if (completedAt == null) {
            payload.putNull("concluidaEm");
        } else {
            payload.put("concluidaEm", completedAt);
        }
        payload.put("createdAt", "2026-07-23T11:00:00.000Z");
        payload.put("updatedAt", OCCURRED_AT);
        if (deletedAt == null) {
            payload.putNull("deletadaEm");
        } else {
            payload.put("deletadaEm", deletedAt);
        }
        return payload;
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String operation,
            String canonicalOperation,
            Long baseVersion,
            ObjectNode payload
    ) {
        return new SyncPushRequest.MutacaoCliente(
                MUTATION,
                "TAREFA",
                TASK,
                operation,
                baseVersion,
                payload,
                LocalDateTime.parse("2026-07-23T12:00:00"),
                MUTATION,
                13,
                DEVICE,
                ACTOR,
                WORKSITE,
                "TAREFA",
                TASK,
                canonicalOperation,
                baseVersion,
                List.of(),
                OCCURRED_AT,
                null,
                null,
                List.of(),
                List.of()
        );
    }
}

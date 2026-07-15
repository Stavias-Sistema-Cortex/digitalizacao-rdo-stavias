package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.memory.OperationalEventTraceContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncServiceHandlerDelegationTest {

    @Test
    void delegatesSupportedMutationAndKeepsGenericResult() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        SyncMutationHandler handler = mock(SyncMutationHandler.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(handler.operations()).thenReturn(Set.of("CRIAR_EQUIPE"));
        when(handler.requiresBaseVersion("CRIAR_EQUIPE")).thenReturn(false);

        ObjectNode domainResult = objectMapper.createObjectNode()
                .put("id", "equipe-1")
                .put("nome", "Equipe Norte")
                .put("versaoEntidade", 1);
        when(handler.apply(any())).thenAnswer(invocation -> {
            OperationalEventTraceContext.Trace trace =
                    OperationalEventTraceContext.current().orElseThrow();
            assertThat(trace.origin()).isEqualTo("OFFLINE");
            assertThat(trace.syncStatus()).isEqualTo("SYNCED");
            assertThat(trace.actorId()).isEqualTo("alfa-1");
            assertThat(trace.deviceId()).isEqualTo("device-1");
            assertThat(trace.correlationId()).isEqualTo("mutation-1");
            assertThat(trace.causationId()).isEqualTo("mutation-1");
            return new SyncMutationApplied(
                    "EQUIPE",
                    "equipe-1",
                    1,
                    91,
                    domainResult
            );
        });

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.queryForObject(
                contains("FROM sync_dispositivo"),
                eq(Integer.class),
                eq("device-1"),
                eq("alfa-1")
        )).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("FROM sync_mutacao_cliente"),
                any(RowMapper.class),
                eq("device-1"),
                eq("mutation-1")
        )).thenThrow(new EmptyResultDataAccessException(1));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        SyncService service = new SyncService(
                jdbcTemplate,
                objectMapper,
                transactionTemplate,
                currentUserService,
                List.of(handler)
        );
        SyncPushRequest.MutacaoCliente mutation = new SyncPushRequest.MutacaoCliente(
                "mutation-1",
                "EQUIPE",
                "equipe-1",
                "CRIAR_EQUIPE",
                null,
                objectMapper.createObjectNode().put("nome", "Equipe Norte"),
                LocalDateTime.of(2026, 7, 15, 8, 0)
        );

        SyncPushResponse response = service.push(new SyncPushRequest(
                "device-1",
                List.of(mutation)
        ));

        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("APLICADA");
            assertThat(result.entidadeTipo()).isEqualTo("EQUIPE");
            assertThat(result.entidadeId()).isEqualTo("equipe-1");
            assertThat(result.eventoServidorCommitSeq()).isEqualTo(91);
            assertThat(result.resultado()).isEqualTo(domainResult);
        });
        verify(handler).apply(mutation);
        assertThat(OperationalEventTraceContext.current()).isEmpty();
    }

    @Test
    void replayReturnsStoredResultWithoutCallingDomainHandlerAgain() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        SyncMutationHandler handler = mock(SyncMutationHandler.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(handler.operations()).thenReturn(Set.of("CRIAR_EQUIPE"));
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.queryForObject(
                contains("FROM sync_dispositivo"),
                eq(Integer.class),
                eq("device-1"),
                eq("alfa-1")
        )).thenReturn(1);

        ObjectNode storedPayload = objectMapper.createObjectNode()
                .put("id", "equipe-1")
                .put("versaoEntidade", 1);
        SyncPushResponse.ResultadoMutacao stored = new SyncPushResponse.ResultadoMutacao(
                "mutation-1",
                "APLICADA",
                "EQUIPE",
                "equipe-1",
                "CRIAR_EQUIPE",
                91L,
                storedPayload,
                objectMapper.createObjectNode(),
                null
        );
        when(jdbcTemplate.queryForObject(
                contains("FROM sync_mutacao_cliente"),
                any(RowMapper.class),
                eq("device-1"),
                eq("mutation-1")
        )).thenReturn(stored);

        SyncService service = new SyncService(
                jdbcTemplate,
                objectMapper,
                transactionTemplate,
                currentUserService,
                List.of(handler)
        );
        SyncPushRequest.MutacaoCliente mutation = new SyncPushRequest.MutacaoCliente(
                "mutation-1",
                "EQUIPE",
                "equipe-1",
                "CRIAR_EQUIPE",
                null,
                objectMapper.createObjectNode(),
                LocalDateTime.of(2026, 7, 15, 8, 0)
        );

        SyncPushResponse response = service.push(new SyncPushRequest(
                "device-1",
                List.of(mutation)
        ));

        assertThat(response.resultados()).containsExactly(stored);
        verify(handler, never()).apply(any());
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void domainRaceConflictIsPersistedAsStructuredDiscardedResult() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        SyncMutationHandler handler = mock(SyncMutationHandler.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(handler.operations()).thenReturn(Set.of("ATUALIZAR_EQUIPE"));
        when(handler.requiresBaseVersion("ATUALIZAR_EQUIPE")).thenReturn(true);
        when(handler.apply(any())).thenThrow(new SyncVersionConflictException(
                "EQUIPE", "equipe-1", 4, 5
        ));
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.queryForObject(
                contains("FROM sync_dispositivo"),
                eq(Integer.class),
                eq("device-1"),
                eq("alfa-1")
        )).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("FROM sync_mutacao_cliente"),
                any(RowMapper.class),
                eq("device-1"),
                eq("mutation-conflict")
        )).thenThrow(new EmptyResultDataAccessException(1));
        when(jdbcTemplate.queryForObject(
                contains("FROM cortex_estado_entidade"),
                eq(Long.class),
                eq("EQUIPE"),
                eq("equipe-1")
        )).thenReturn(4L);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        SyncService service = new SyncService(
                jdbcTemplate,
                objectMapper,
                transactionTemplate,
                currentUserService,
                List.of(handler)
        );
        SyncPushRequest.MutacaoCliente mutation = new SyncPushRequest.MutacaoCliente(
                "mutation-conflict",
                "EQUIPE",
                "equipe-1",
                "ATUALIZAR_EQUIPE",
                4L,
                objectMapper.createObjectNode(),
                LocalDateTime.of(2026, 7, 15, 14, 30)
        );

        SyncPushResponse response = service.push(new SyncPushRequest(
                "device-1",
                List.of(mutation)
        ));

        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("DESCARTADA");
            assertThat(result.erro()).isEqualTo("Conflito de versão.");
            assertThat(result.conflito().path("entidadeTipo").asText()).isEqualTo("EQUIPE");
            assertThat(result.conflito().path("entidadeId").asText()).isEqualTo("equipe-1");
            assertThat(result.conflito().path("baseVersao").asLong()).isEqualTo(4);
            assertThat(result.conflito().path("versaoAtual").asLong()).isEqualTo(5);
        });
        verify(jdbcTemplate).update(
                contains("ON DUPLICATE KEY UPDATE"),
                any(Object[].class)
        );
    }
}

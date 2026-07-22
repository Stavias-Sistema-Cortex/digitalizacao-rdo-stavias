package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class SyncMutationTraceValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalRequestCarriesTheCompleteOfflineTrace() throws Exception {
        ObjectNode changed = objectMapper.createObjectNode().put("titulo", "Novo");
        ObjectNode base = objectMapper.createObjectNode().put("titulo", "Antigo");
        String actorId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        SyncPushRequest.MutacaoCliente mutation = mutation(
                actorId,
                List.of(UUID.randomUUID().toString()),
                eventId,
                payloadHash(changed),
                new SyncPushRequest.FieldPatch(changed, base)
        );

        assertThat(mutation.actorId()).isEqualTo(actorId);
        assertThat(mutation.authorizationScope()).hasSize(1);
        assertThat(mutation.ontologyEventId()).isEqualTo(eventId);
        assertThat(mutation.payloadHash()).hasSize(64);
        assertThat(mutation.fieldPatch().changed()).isEqualTo(changed);
        assertThat(mutation.fieldPatch().baseValues()).isEqualTo(base);
        assertThat(mutation.causationId()).isEqualTo("cause-1");
        assertThat(mutation.dependsOnMutationIds()).containsExactly("dependency-1");
    }

    @Test
    void rejectsActorMismatchBeforeCallingTheDomainHandler() throws Exception {
        String authenticatedActor = UUID.randomUUID().toString();
        String claimedActor = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String scopeId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode().put("obraId", scopeId);
        SyncOperationHandler handler = handler();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        TransactionTemplate transactions = immediateTransactions();

        when(currentUser.requireUserId()).thenReturn(authenticatedActor);
        when(currentUser.allowedObraIds(authenticatedActor))
                .thenReturn(Optional.of(Set.of(scopeId)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(deviceId), eq(authenticatedActor)))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        SyncService service = new SyncService(
                jdbc,
                objectMapper,
                transactions,
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );
        SyncPushRequest.MutacaoCliente mutation = mutation(
                claimedActor,
                List.of(scopeId),
                UUID.randomUUID().toString(),
                payloadHash(payload),
                new SyncPushRequest.FieldPatch(payload, objectMapper.createObjectNode())
        );

        SyncPushResponse response = service.push(
                new SyncPushRequest(deviceId, List.of(mutation))
        );

        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("REJEITADA");
            assertThat(result.resultado().path("rejeicao").path("categoria").asText())
                    .isEqualTo("ACTOR_MISMATCH");
        });
        verify(handler, never()).apply(any(), any());
        ArgumentCaptor<Object[]> rejectedArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("evento_cliente_id"), rejectedArgs.capture());
        String rejectedSql = sqlForUpdateContaining(jdbc, "INSERT INTO sync_mutacao_cliente");
        assertThat(rejectedSql.chars().filter(character -> character == '?').count())
                .isEqualTo(rejectedArgs.getValue().length);
    }

    @Test
    void rejectsMalformedIdsInvalidHashAndUnsupportedScopeBeforeHandler() throws Exception {
        String actorId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String allowedScope = UUID.randomUUID().toString();
        String unsupportedScope = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode().put("obraId", allowedScope);
        SyncOperationHandler handler = handler();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);

        when(currentUser.requireUserId()).thenReturn(actorId);
        when(currentUser.allowedObraIds(actorId))
                .thenReturn(Optional.of(Set.of(allowedScope)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(deviceId), eq(actorId)))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        SyncService service = new SyncService(
                jdbc,
                objectMapper,
                immediateTransactions(),
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );

        assertRejected(
                service,
                deviceId,
                mutation(actorId, List.of(allowedScope), "not-a-uuid",
                        payloadHash(payload), fieldPatch(payload)),
                "MALFORMED_ONTOLOGY_EVENT_ID"
        );
        assertRejected(
                service,
                deviceId,
                mutation(actorId, List.of(allowedScope), UUID.randomUUID().toString(),
                        "0".repeat(64), fieldPatch(payload)),
                "PAYLOAD_HASH_MISMATCH"
        );
        assertRejected(
                service,
                deviceId,
                mutation(actorId, List.of(unsupportedScope), UUID.randomUUID().toString(),
                        payloadHash(payload), fieldPatch(payload)),
                "UNSUPPORTED_AUTHORIZATION_SCOPE"
        );
        assertRejected(
                service,
                deviceId,
                mutation("not-a-uuid", List.of(allowedScope), UUID.randomUUID().toString(),
                        payloadHash(payload), fieldPatch(payload)),
                "MALFORMED_ACTOR_ID"
        );

        verify(handler, never()).apply(any(), any());
    }

    @Test
    void rejectsAnUnregisteredDeviceBeforeHandlerExecution() throws Exception {
        String actorId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String scopeId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode().put("obraId", scopeId);
        SyncOperationHandler handler = handler();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);

        when(currentUser.requireUserId()).thenReturn(actorId);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(deviceId), eq(actorId)))
                .thenReturn(0);

        SyncService service = new SyncService(
                jdbc,
                objectMapper,
                immediateTransactions(),
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );

        assertThatThrownBy(() -> service.push(new SyncPushRequest(
                deviceId,
                List.of(mutation(
                        actorId,
                        List.of(scopeId),
                        UUID.randomUUID().toString(),
                        payloadHash(payload),
                        fieldPatch(payload)
                ))
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Dispositivo não pertence");
        verify(handler, never()).apply(any(), any());
    }

    @Test
    void mapsOntologyEventToClientColumnAndKeepsServerCommitAuthoritative()
            throws Exception {
        String actorId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String scopeId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String entityId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("zeta", 2);
        payload.put("alpha", 1);
        payload.put("decimalInteiro", 1.0d);
        SyncOperationHandler handler = handler();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);

        when(currentUser.requireUserId()).thenReturn(actorId);
        when(currentUser.allowedObraIds(actorId)).thenReturn(Optional.of(Set.of(scopeId)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(deviceId), eq(actorId)))
                .thenReturn(1);
        when(handler.requiresBaseVersion("CRIAR_RDO")).thenReturn(false);
        when(handler.apply(any(), any())).thenReturn(new AppliedSyncMutation(
                "RDO",
                entityId,
                objectMapper.createObjectNode()
        ));
        when(jdbc.queryForObject(
                contains("SELECT ev.commit_seq"),
                eq(Long.class),
                eq("RDO"),
                eq(entityId)
        )).thenReturn(77L);
        when(jdbc.queryForObject(
                contains("SELECT versao_entidade"),
                eq(Long.class),
                eq("RDO"),
                eq(entityId)
        )).thenReturn(4L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        SyncService service = new SyncService(
                jdbc,
                objectMapper,
                immediateTransactions(),
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );
        SyncPushResponse response = service.push(new SyncPushRequest(
                deviceId,
                List.of(mutation(
                        actorId,
                        List.of(scopeId),
                        eventId,
                        canonicalHash("{\"alpha\":1,\"decimalInteiro\":1,\"zeta\":2}"),
                        fieldPatch(payload)
                ))
        ));

        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("APLICADA");
            assertThat(result.eventoServidorCommitSeq()).isEqualTo(77L);
        });
        ArgumentCaptor<Object[]> pendingArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(
                contains("INSERT INTO sync_mutacao_cliente"),
                pendingArgs.capture()
        );
        String pendingSql = sqlForUpdateContaining(jdbc, "INSERT INTO sync_mutacao_cliente");
        assertThat(pendingSql.chars().filter(character -> character == '?').count())
                .isEqualTo(pendingArgs.getValue().length);
        assertThat(pendingArgs.getValue()).contains(eventId);
        assertThat(pendingArgs.getValue()).doesNotContain(77L);
        ArgumentCaptor<Object[]> appliedArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(
                contains("evento_servidor_commit_seq = ?"),
                appliedArgs.capture()
        );
        assertThat(appliedArgs.getValue()).contains(77L);
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String actorId,
            List<String> authorizationScope,
            String ontologyEventId,
            String payloadHash,
            SyncPushRequest.FieldPatch fieldPatch
    ) {
        return new SyncPushRequest.MutacaoCliente(
                "mutation-1",
                "RDO",
                null,
                "CRIAR_RDO",
                null,
                fieldPatch.changed(),
                LocalDateTime.now(),
                "correlation-1",
                fieldPatch,
                actorId,
                authorizationScope,
                ontologyEventId,
                payloadHash,
                "cause-1",
                List.of("dependency-1")
        );
    }

    private SyncPushRequest.FieldPatch fieldPatch(ObjectNode changed) {
        return new SyncPushRequest.FieldPatch(
                changed,
                objectMapper.createObjectNode()
        );
    }

    private void assertRejected(
            SyncService service,
            String deviceId,
            SyncPushRequest.MutacaoCliente mutation,
            String category
    ) {
        SyncPushResponse response = service.push(
                new SyncPushRequest(deviceId, List.of(mutation))
        );
        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("REJEITADA");
            assertThat(result.resultado().path("rejeicao").path("categoria").asText())
                    .isEqualTo(category);
        });
    }

    private String sqlForUpdateContaining(JdbcTemplate jdbc, String fragment) {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update"))
                .map(invocation -> invocation.getArgument(0, String.class))
                .filter(sql -> sql.contains(fragment))
                .findFirst()
                .orElseThrow();
    }

    private SyncOperationHandler handler() {
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        when(handler.entityType()).thenReturn("RDO");
        when(handler.operations()).thenReturn(Set.of("CRIAR_RDO"));
        return handler;
    }

    private TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation
                .<TransactionCallback<?>>getArgument(0)
                .doInTransaction(null))
                .when(transactions).execute(any());
        return transactions;
    }

    private String payloadHash(ObjectNode payload) throws Exception {
        return canonicalHash(objectMapper.writeValueAsString(payload));
    }

    private String canonicalHash(String canonicalJson) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                canonicalJson.getBytes(StandardCharsets.UTF_8)
        ));
    }
}

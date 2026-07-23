package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SyncMutationTraceValidationTest {

    private static final String OCCURRED_AT = "2026-07-21T12:00:00.000Z";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void v46AddsCanonicalReceiptAndEventBindingsWithoutReplacingLegacyKey()
            throws Exception {
        Path migration = Path.of(
                "src/main/resources/db/migration-postgresql/"
                        + "V46__canonical_mutation_trace.sql"
        );

        assertThat(migration).exists();
        assertThat(Files.readString(migration))
                .contains("ADD COLUMN schema_version integer NOT NULL DEFAULT 1")
                .contains("ADD COLUMN changed_fields_json jsonb NOT NULL DEFAULT '[]'::jsonb")
                .contains("ADD COLUMN operacao_canonica varchar(20)")
                .contains("ADD COLUMN client_mutation_id varchar(120)")
                .contains("uq_sync_mutation_owner_client_v13")
                .contains("WHERE schema_version = 13")
                .doesNotContain("DROP CONSTRAINT uq_sync_mutacao_cliente")
                .doesNotContain("DROP INDEX uq_sync_mutacao_cliente");
    }

    @Test
    void canonicalRequestCarriesExactV13EnvelopeAndTrace() throws Exception {
        Fixture fixture = fixture();
        SyncPushRequest.MutacaoCliente mutation = fixture.mutation();

        assertThat(mutation.schemaVersion()).isEqualTo(13);
        assertThat(mutation.deviceId()).isEqualTo(fixture.deviceId());
        assertThat(mutation.userId()).isEqualTo(fixture.actorId());
        assertThat(mutation.obraId()).isEqualTo(fixture.obraId());
        assertThat(mutation.entityType()).isEqualTo("RDO");
        assertThat(mutation.entityId()).isEqualTo(fixture.entityId());
        assertThat(mutation.operation()).isEqualTo("CREATE");
        assertThat(mutation.baseVersion()).isNull();
        assertThat(mutation.changedFields()).containsExactly("id", "obraId");
        assertThat(mutation.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(mutation.trace().actorId()).isEqualTo(fixture.actorId());
        assertThat(mutation.trace().payloadHash()).hasSize(64);
        assertThat(mutation.relatedEntities()).singleElement().satisfies(related -> {
            assertThat(related.tipo()).isEqualTo("RDO");
            assertThat(related.id()).isEqualTo(fixture.relatedId());
        });
    }

    @Test
    void rejectsCanonicalIdentityAliasAndHashMismatchBeforeHandler() throws Exception {
        Fixture fixture = fixture();
        Harness harness = harness(fixture);

        assertRejected(
                harness.service(),
                fixture.deviceId(),
                fixture.withUserId(UUID.randomUUID().toString()),
                "USER_MISMATCH"
        );
        assertRejected(
                harness.service(),
                fixture.deviceId(),
                fixture.withDeviceId(UUID.randomUUID().toString()),
                "DEVICE_MISMATCH"
        );
        assertRejected(
                harness.service(),
                fixture.deviceId(),
                fixture.withAliasOperation("ATUALIZAR_RDO_RASCUNHO"),
                "OPERATION_ALIAS_MISMATCH"
        );
        assertRejected(
                harness.service(),
                fixture.deviceId(),
                fixture.withPayloadHash("0".repeat(64)),
                "PAYLOAD_HASH_MISMATCH"
        );

        verify(harness.handler(), never()).apply(any(), any());
    }

    @Test
    void rejectsRelatedEntityOutsideAuthorizedWorksiteBeforeHandler() throws Exception {
        Fixture fixture = fixture();
        Harness harness = harness(fixture);
        when(harness.jdbc().queryForObject(
                contains("FROM rdo"),
                eq(String.class),
                eq(fixture.relatedId())
        )).thenReturn(UUID.randomUUID().toString());

        assertRejected(
                harness.service(),
                fixture.deviceId(),
                fixture.mutation(),
                "RELATED_ENTITY_SCOPE"
        );

        verify(harness.handler(), never()).apply(any(), any());
    }

    @Test
    void rejectsDuplicateAndSelfDependenciesBeforeHandler() throws Exception {
        Fixture fixture = fixture();
        Harness harness = harness(fixture);
        SyncPushRequest.MutacaoCliente original = fixture.mutation();
        String dependency = UUID.randomUUID().toString();

        assertRejected(
                harness.service(),
                fixture.deviceId(),
                fixture.withDependencies(original, List.of(dependency, dependency)),
                "DUPLICATE_DEPENDENCY_ID"
        );
        assertRejected(
                harness.service(),
                fixture.deviceId(),
                fixture.withDependencies(
                        original,
                        List.of(original.clientMutationId())
                ),
                "SELF_DEPENDENCY"
        );

        verify(harness.handler(), never()).apply(any(), any());
    }

    @Test
    void keepsCanonicalMutationRetryableUntilAllDependenciesAreApplied()
            throws Exception {
        Fixture fixture = fixture();
        Harness harness = harness(fixture);
        SyncPushRequest.MutacaoCliente original = fixture.mutation();
        SyncPushRequest.MutacaoCliente dependent = fixture.withDependencies(
                original,
                List.of(UUID.randomUUID().toString())
        );
        when(harness.jdbc().queryForObject(
                contains("FROM rdo"),
                eq(String.class),
                eq(fixture.relatedId())
        )).thenReturn(fixture.obraId());
        when(harness.jdbc().queryForObject(
                contains("FROM sync_mutacao_cliente"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);

        SyncPushResponse response = harness.service().push(
                new SyncPushRequest(fixture.deviceId(), List.of(dependent))
        );

        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("ERRO");
            assertThat(result.erro()).contains("dependências causais");
        });
        verify(harness.handler(), never()).apply(any(), any());
    }

    @Test
    void acceptsGlobalServiceReferenceWhenCreatingPriceForAnotherAuthorizedWorksite()
            throws Exception {
        String actorId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String obraId = UUID.randomUUID().toString();
        String serviceAuthorizingWorksite = UUID.randomUUID().toString();
        String priceId = UUID.randomUUID().toString();
        String serviceId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", priceId);
        payload.put("obraId", obraId);
        payload.put("serviceId", serviceId);

        SyncPushRequest.MutacaoCliente mutation = new SyncPushRequest.MutacaoCliente(
                UUID.randomUUID().toString(),
                "SERVICE_PRICE_VERSION",
                priceId,
                "CRIAR_PRECO_SERVICO",
                null,
                payload,
                LocalDateTime.parse("2026-07-21T12:00:00"),
                correlationId,
                13,
                deviceId,
                actorId,
                obraId,
                "SERVICE_PRICE_VERSION",
                priceId,
                "CREATE",
                null,
                List.of("id", "obraId", "serviceId"),
                OCCURRED_AT,
                new SyncPushRequest.MutationTrace(
                        actorId,
                        deviceId,
                        List.of(obraId),
                        correlationId,
                        null,
                        UUID.randomUUID().toString(),
                        sha256("{\"id\":\"" + priceId
                                + "\",\"obraId\":\"" + obraId
                                + "\",\"serviceId\":\"" + serviceId + "\"}")
                ),
                new SyncPushRequest.FieldPatch(payload, mapper.createObjectNode()),
                List.of(new SyncPushRequest.RelatedEntity(
                        "SERVICE", serviceId, "Serviço global"
                )),
                List.of()
        );

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        when(handler.entityType()).thenReturn("SERVICE_PRICE_VERSION");
        when(handler.operations()).thenReturn(Set.of("CRIAR_PRECO_SERVICO"));
        when(currentUser.requireUserId()).thenReturn(actorId);
        when(currentUser.allowedObraIds(actorId)).thenReturn(Optional.of(Set.of(obraId)));
        when(jdbc.queryForObject(
                anyString(), eq(Integer.class), eq(deviceId), eq(actorId)
        )).thenReturn(1);
        when(jdbc.queryForObject(
                contains("FROM catalogo_servico"), eq(String.class), eq(serviceId)
        )).thenReturn(serviceAuthorizingWorksite);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        SyncService service = new SyncService(
                jdbc,
                mapper,
                immediateTransactions(),
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );

        service.push(new SyncPushRequest(deviceId, List.of(mutation)));

        verify(handler).apply(eq(mutation), any());
    }

    @Test
    void legacyEightFieldRequestRemainsSupported() {
        SyncPushRequest.MutacaoCliente legacy = new SyncPushRequest.MutacaoCliente(
                "legacy-client-id",
                "RDO",
                null,
                "CRIAR_RDO",
                null,
                mapper.createObjectNode().put("obraId", "legacy-worksite"),
                LocalDateTime.parse("2026-07-21T12:00:00"),
                "legacy-correlation"
        );

        assertThat(legacy.schemaVersion()).isNull();
        assertThat(legacy.clientMutationId()).isEqualTo("legacy-client-id");
        assertThat(legacy.operacao()).isEqualTo("CRIAR_RDO");
    }

    private Harness harness(Fixture fixture) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        when(handler.entityType()).thenReturn("RDO");
        when(handler.operations()).thenReturn(Set.of("CRIAR_RDO", "ATUALIZAR_RDO_RASCUNHO"));
        when(currentUser.requireUserId()).thenReturn(fixture.actorId());
        when(currentUser.allowedObraIds(fixture.actorId()))
                .thenReturn(Optional.of(Set.of(fixture.obraId())));
        when(jdbc.queryForObject(
                anyString(),
                eq(Integer.class),
                eq(fixture.deviceId()),
                eq(fixture.actorId())
        )).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        return new Harness(
                jdbc,
                handler,
                new SyncService(
                        jdbc,
                        mapper,
                        immediateTransactions(),
                        new SyncOperationRegistry(List.of(handler)),
                        currentUser,
                        mock(FinancialAccessService.class)
                )
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

    private TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation
                .<TransactionCallback<?>>getArgument(0)
                .doInTransaction(null))
                .when(transactions).execute(any());
        return transactions;
    }

    private Fixture fixture() throws Exception {
        String actorId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String obraId = UUID.randomUUID().toString();
        String entityId = UUID.randomUUID().toString();
        String relatedId = UUID.randomUUID().toString();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", entityId);
        payload.put("obraId", obraId);
        String hash = sha256("{\"id\":\"" + entityId
                + "\",\"obraId\":\"" + obraId + "\"}");
        return new Fixture(actorId, deviceId, obraId, entityId, relatedId, hash);
    }

    private String sha256(String canonicalJson) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(canonicalJson.getBytes(StandardCharsets.UTF_8))
        );
    }

    private record Harness(
            JdbcTemplate jdbc,
            SyncOperationHandler handler,
            SyncService service
    ) {
    }

    private record Fixture(
            String actorId,
            String deviceId,
            String obraId,
            String entityId,
            String relatedId,
            String payloadHash
    ) {
        private SyncPushRequest.MutacaoCliente mutation() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("id", entityId);
            payload.put("obraId", obraId);
            String clientId = UUID.randomUUID().toString();
            String correlationId = UUID.randomUUID().toString();
            return new SyncPushRequest.MutacaoCliente(
                    clientId,
                    "RDO",
                    entityId,
                    "CRIAR_RDO",
                    null,
                    payload,
                    LocalDateTime.parse("2026-07-21T12:00:00"),
                    correlationId,
                    13,
                    deviceId,
                    actorId,
                    obraId,
                    "RDO",
                    entityId,
                    "CREATE",
                    null,
                    List.of("id", "obraId"),
                    OCCURRED_AT,
                    new SyncPushRequest.MutationTrace(
                            actorId,
                            deviceId,
                            List.of(obraId),
                            correlationId,
                            null,
                            UUID.randomUUID().toString(),
                            payloadHash
                    ),
                    new SyncPushRequest.FieldPatch(
                            payload,
                            mapper.createObjectNode()
                    ),
                    List.of(new SyncPushRequest.RelatedEntity(
                            "RDO",
                            relatedId,
                            "RDO anterior"
                    )),
                    List.of()
            );
        }

        private SyncPushRequest.MutacaoCliente withUserId(String value) {
            return copy(value, deviceId, "CRIAR_RDO", payloadHash);
        }

        private SyncPushRequest.MutacaoCliente withDeviceId(String value) {
            return copy(actorId, value, "CRIAR_RDO", payloadHash);
        }

        private SyncPushRequest.MutacaoCliente withAliasOperation(String value) {
            return copy(actorId, deviceId, value, payloadHash);
        }

        private SyncPushRequest.MutacaoCliente withPayloadHash(String value) {
            return copy(actorId, deviceId, "CRIAR_RDO", value);
        }

        private SyncPushRequest.MutacaoCliente withDependencies(
                SyncPushRequest.MutacaoCliente original,
                List<String> dependencies
        ) {
            return new SyncPushRequest.MutacaoCliente(
                    original.clientMutationId(), original.entidadeTipo(),
                    original.entidadeId(), original.operacao(),
                    original.baseVersao(), original.payload(),
                    original.criadaNoClienteEm(), original.correlacaoId(),
                    original.schemaVersion(), original.deviceId(),
                    original.userId(), original.obraId(), original.entityType(),
                    original.entityId(), original.operation(),
                    original.baseVersion(), original.changedFields(),
                    original.occurredAt(), original.trace(),
                    original.fieldPatch(), original.relatedEntities(),
                    dependencies
            );
        }

        private SyncPushRequest.MutacaoCliente copy(
                String newUserId,
                String newDeviceId,
                String aliasOperation,
                String newPayloadHash
        ) {
            SyncPushRequest.MutacaoCliente original = mutation();
            return new SyncPushRequest.MutacaoCliente(
                    original.clientMutationId(), original.entidadeTipo(), original.entidadeId(),
                    aliasOperation, original.baseVersao(), original.payload(),
                    original.criadaNoClienteEm(), original.correlacaoId(), original.schemaVersion(),
                    newDeviceId, newUserId, original.obraId(), original.entityType(),
                    original.entityId(), original.operation(), original.baseVersion(),
                    original.changedFields(), original.occurredAt(),
                    new SyncPushRequest.MutationTrace(
                            newUserId,
                            newDeviceId,
                            original.trace().authorizationScope(),
                            original.trace().correlationId(),
                            original.trace().causationId(),
                            original.trace().ontologyEventId(),
                            newPayloadHash
                    ),
                    original.fieldPatch(), original.relatedEntities(),
                    original.dependsOnMutationIds()
            );
        }
    }
}

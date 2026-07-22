package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlCanonicalMutationIT {

    private static final String OCCURRED_AT = "2026-07-21T12:00:00.000Z";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_canonical_sync_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void replayByOwnerAndClientReturnsOriginalCommitAcrossDevices() throws Exception {
        Setup setup = setup(false);
        String clientId = UUID.randomUUID().toString();
        String entityId = UUID.randomUUID().toString();
        SyncPushRequest.MutacaoCliente mutation = canonicalMutation(
                setup,
                setup.deviceOne(),
                clientId,
                entityId,
                "applied"
        );

        SyncPushResponse first = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mutation))
        );
        SyncPushResponse otherDeviceReplay = setup.service().push(
                new SyncPushRequest(setup.deviceTwo(), List.of(mutation))
        );

        assertThat(first.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("APLICADA");
            assertThat(result.eventoServidorCommitSeq()).isNotNull();
        });
        assertThat(otherDeviceReplay.resultados().getFirst().eventoServidorCommitSeq())
                .isEqualTo(first.resultados().getFirst().eventoServidorCommitSeq());
        assertThat(setup.domainWrites()).hasValue(1);

        assertThat(jdbc.queryForMap(
                """
                SELECT schema_version, proprietario_id, dispositivo_id, obra_id,
                       operacao_canonica, payload_hash, envelope_hash,
                       jsonb_typeof(changed_fields_json) AS changed_type,
                       jsonb_typeof(entidades_relacionadas_json) AS related_type,
                       evento_servidor_commit_seq
                FROM sync_mutacao_cliente
                WHERE proprietario_id = ? AND client_mutation_id = ?
                """,
                setup.ownerId(),
                clientId
        )).containsEntry("schema_version", 13)
                .containsEntry("proprietario_id", setup.ownerId())
                .containsEntry("dispositivo_id", setup.deviceOne())
                .containsEntry("obra_id", setup.obraId())
                .containsEntry("operacao_canonica", "CREATE")
                .containsEntry("changed_type", "array")
                .containsEntry("related_type", "array");

        assertThat(jdbc.queryForMap(
                """
                SELECT schema_version, usuario_id, dispositivo_id, correlacao_id,
                       client_mutation_id, evento_cliente_id, resultado,
                       jsonb_typeof(estado_novo_json) AS state_type
                FROM cortex_evento_operacional
                WHERE commit_seq = ?
                """,
                first.resultados().getFirst().eventoServidorCommitSeq()
        )).containsEntry("schema_version", 13)
                .containsEntry("usuario_id", setup.ownerId())
                .containsEntry("dispositivo_id", setup.deviceOne())
                .containsEntry("client_mutation_id", clientId)
                .containsEntry("evento_cliente_id", mutation.trace().ontologyEventId())
                .containsEntry("resultado", "SUCESSO")
                .containsEntry("state_type", "object");

        SyncPushRequest.MutacaoCliente mismatched = canonicalMutation(
                setup,
                setup.deviceOne(),
                clientId,
                entityId,
                "changed"
        );
        SyncPushResponse mismatch = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mismatched))
        );
        assertThat(mismatch.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("REJEITADA");
            assertThat(result.resultado().path("rejeicao").path("categoria").asText())
                    .isEqualTo("IDEMPOTENCY_MISMATCH");
        });
        assertThat(setup.domainWrites()).hasValue(1);

        jdbc.update(
                "UPDATE sync_mutacao_cliente SET envelope_hash = NULL "
                        + "WHERE proprietario_id = ? AND client_mutation_id = ?",
                setup.ownerId(),
                clientId
        );
        SyncPushResponse corruptedReceiptReplay = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mutation))
        );
        assertThat(corruptedReceiptReplay.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("REJEITADA");
            assertThat(result.resultado().path("rejeicao").path("categoria").asText())
                    .isEqualTo("IDEMPOTENCY_MISMATCH");
        });
        assertThat(setup.domainWrites()).hasValue(1);
    }

    @Test
    void failedCanonicalMutationReopensAndAppliesExactlyOnceOnRetry() throws Exception {
        Setup setup = setup(true);
        String clientId = UUID.randomUUID().toString();
        SyncPushRequest.MutacaoCliente mutation = canonicalMutation(
                setup,
                setup.deviceOne(),
                clientId,
                UUID.randomUUID().toString(),
                "fail-once"
        );

        SyncPushResponse failed = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mutation))
        );
        SyncPushResponse retried = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mutation))
        );
        SyncPushResponse replay = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mutation))
        );

        assertThat(failed.resultados().getFirst().status()).isEqualTo("ERRO");
        assertThat(retried.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(replay.resultados().getFirst().eventoServidorCommitSeq())
                .isEqualTo(retried.resultados().getFirst().eventoServidorCommitSeq());
        assertThat(setup.attempts()).hasValue(2);
        assertThat(setup.retryObservedPending()).isTrue();
        assertThat(setup.domainWrites()).hasValue(1);
    }

    @Test
    void failedLegacyRetryPreservesOriginalHashAndRejectsChangedReplay() {
        Setup setup = setup(true);
        String clientId = "legacy-" + UUID.randomUUID();
        String entityId = UUID.randomUUID().toString();
        SyncPushRequest.MutacaoCliente mutation = legacyMutation(
                setup,
                clientId,
                entityId,
                "fail-once"
        );

        SyncPushResponse failed = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mutation))
        );
        Map<String, Object> failedReceipt = jdbc.queryForMap(
                "SELECT payload_hash, envelope_hash FROM sync_mutacao_cliente "
                        + "WHERE dispositivo_id = ? AND client_mutation_id = ?",
                setup.deviceOne(),
                clientId
        );
        SyncPushResponse retried = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(mutation))
        );
        Map<String, Object> appliedReceipt = jdbc.queryForMap(
                "SELECT payload_hash, envelope_hash FROM sync_mutacao_cliente "
                        + "WHERE dispositivo_id = ? AND client_mutation_id = ?",
                setup.deviceOne(),
                clientId
        );
        SyncPushResponse changedReplay = setup.service().push(
                new SyncPushRequest(
                        setup.deviceOne(),
                        List.of(legacyMutation(setup, clientId, entityId, "changed"))
                )
        );

        assertThat(failed.resultados().getFirst().status()).isEqualTo("ERRO");
        assertThat(retried.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(failedReceipt.get("payload_hash")).isNotNull();
        assertThat(appliedReceipt.get("payload_hash"))
                .isEqualTo(failedReceipt.get("payload_hash"));
        assertThat(failedReceipt.get("envelope_hash")).isNull();
        assertThat(appliedReceipt.get("envelope_hash")).isNull();
        assertThat(changedReplay.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("ERRO");
            assertThat(result.erro()).contains("outro conteúdo");
            assertThat(result.eventoServidorCommitSeq()).isNull();
        });
        assertThat(setup.attempts()).hasValue(2);
        assertThat(setup.domainWrites()).hasValue(1);
    }

    @Test
    void malformedCanonicalProvenanceIsRejectedWithoutDurableReceipt() throws Exception {
        Setup setup = setup(false);
        SyncPushRequest.MutacaoCliente valid = canonicalMutation(
                setup,
                setup.deviceOne(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "valid"
        );
        SyncPushRequest.MutacaoCliente badHash = copyCanonical(
                valid,
                valid.payload().deepCopy(),
                valid.entityId(),
                "0".repeat(64),
                valid.changedFields()
        );
        SyncPushRequest.MutacaoCliente validFields = canonicalMutation(
                setup,
                setup.deviceOne(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "valid-fields"
        );
        ArrayList<String> fieldsWithNull = new ArrayList<>(validFields.changedFields());
        fieldsWithNull.add(null);
        SyncPushRequest.MutacaoCliente badFields = copyCanonical(
                validFields,
                validFields.payload().deepCopy(),
                validFields.entityId(),
                validFields.trace().payloadHash(),
                fieldsWithNull
        );

        SyncPushResponse badHashResponse = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(badHash))
        );
        SyncPushResponse badFieldsResponse = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(badFields))
        );

        assertRejected(badHashResponse, "PAYLOAD_HASH_MISMATCH");
        assertRejected(badFieldsResponse, "MALFORMED_CHANGED_FIELDS");
        assertThat(setup.attempts()).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_mutacao_cliente "
                        + "WHERE proprietario_id = ? AND client_mutation_id IN (?, ?)",
                Integer.class,
                setup.ownerId(),
                badHash.clientMutationId(),
                badFields.clientMutationId()
        )).isZero();
    }

    @Test
    void forgedCanonicalFieldPatchValuesAreRejectedWithoutHandlerOrLedger()
            throws Exception {
        Setup setup = setup(false);

        SyncPushRequest.MutacaoCliente nestedBase = canonicalMutation(
                setup,
                setup.deviceOne(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "nested"
        );
        ObjectNode nestedPayload = nestedBase.payload().deepCopy();
        ObjectNode nestedValue = mapper.createObjectNode();
        nestedValue.putNull("nullable");
        nestedValue.putObject("details").put("status", "applied");
        nestedPayload.set("value", nestedValue);
        ObjectNode forgedNestedChanged = nestedPayload.deepCopy();
        forgedNestedChanged.withObject("value")
                .withObject("details")
                .put("status", "forged");
        SyncPushRequest.MutacaoCliente forgedNested = copyCanonicalWithPatch(
                nestedBase,
                nestedPayload,
                hash(nestedPayload),
                new SyncPushRequest.FieldPatch(
                        forgedNestedChanged,
                        mapper.createObjectNode()
                )
        );

        SyncPushRequest.MutacaoCliente nullBase = canonicalMutation(
                setup,
                setup.deviceOne(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "nullable"
        );
        ObjectNode nullPayload = nullBase.payload().deepCopy();
        nullPayload.putNull("value");
        ObjectNode nullChanged = nullPayload.deepCopy();
        nullChanged.remove("value");
        ObjectNode nullBaseValues = mapper.createObjectNode();
        nullBaseValues.put("value", "previous");
        SyncPushRequest.MutacaoCliente forgedNull = copyCanonicalWithPatch(
                nullBase,
                nullPayload,
                hash(nullPayload),
                new SyncPushRequest.FieldPatch(nullChanged, nullBaseValues)
        );

        SyncPushRequest.MutacaoCliente deletionBase = canonicalMutation(
                setup,
                setup.deviceOne(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "deleted"
        );
        ObjectNode deletionPayload = deletionBase.payload().deepCopy();
        deletionPayload.remove("value");
        ObjectNode forgedDeletionChanged = deletionPayload.deepCopy();
        forgedDeletionChanged.putNull("value");
        SyncPushRequest.MutacaoCliente forgedDeletion = copyCanonicalWithPatch(
                deletionBase,
                deletionPayload,
                hash(deletionPayload),
                new SyncPushRequest.FieldPatch(
                        forgedDeletionChanged,
                        mapper.createObjectNode()
                )
        );

        SyncPushResponse nestedResponse = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(forgedNested))
        );
        SyncPushResponse nullResponse = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(forgedNull))
        );
        SyncPushResponse deletionResponse = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(forgedDeletion))
        );

        assertRejected(nestedResponse, "CHANGED_FIELD_VALUE_MISMATCH");
        assertRejected(nullResponse, "CHANGED_FIELD_VALUE_MISMATCH");
        assertRejected(deletionResponse, "CHANGED_FIELD_VALUE_MISMATCH");
        assertThat(setup.attempts()).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_mutacao_cliente "
                        + "WHERE proprietario_id = ? AND client_mutation_id IN (?, ?, ?)",
                Integer.class,
                setup.ownerId(),
                forgedNested.clientMutationId(),
                forgedNull.clientMutationId(),
                forgedDeletion.clientMutationId()
        )).isZero();
    }

    @Test
    void canonicalCreateBindsPayloadAndAppliedIdentityWithFullRollback() throws Exception {
        String wrongAppliedId = UUID.randomUUID().toString();
        Setup setup = setup(false, wrongAppliedId);
        SyncPushRequest.MutacaoCliente valid = canonicalMutation(
                setup,
                setup.deviceOne(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "identity"
        );
        ObjectNode mismatchedPayload = valid.payload().deepCopy();
        mismatchedPayload.put("id", UUID.randomUUID().toString());
        SyncPushRequest.MutacaoCliente payloadMismatch = copyCanonical(
                valid,
                mismatchedPayload,
                valid.entityId(),
                hash(mismatchedPayload),
                valid.changedFields()
        );

        SyncPushResponse payloadMismatchResponse = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(payloadMismatch))
        );
        assertRejected(payloadMismatchResponse, "PRINCIPAL_ENTITY_ID_MISMATCH");
        assertThat(setup.attempts()).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_mutacao_cliente "
                        + "WHERE proprietario_id = ? AND client_mutation_id = ?",
                Integer.class,
                setup.ownerId(),
                payloadMismatch.clientMutationId()
        )).isZero();

        SyncPushRequest.MutacaoCliente appliedMismatch = canonicalMutation(
                setup,
                setup.deviceOne(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "applied-mismatch"
        );
        SyncPushResponse appliedMismatchResponse = setup.service().push(
                new SyncPushRequest(setup.deviceOne(), List.of(appliedMismatch))
        );

        assertThat(appliedMismatchResponse.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("ERRO");
            assertThat(result.erro()).contains("aplicação inválida");
        });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_evento_operacional "
                        + "WHERE entidade_id = ? OR client_mutation_id = ?",
                Integer.class,
                wrongAppliedId,
                appliedMismatch.clientMutationId()
        )).isZero();
    }

    private Setup setup(boolean failOnce) {
        return setup(failOnce, null);
    }

    private Setup setup(boolean failOnce, String appliedEntityOverride) {
        String ownerId = collaborator();
        String obraId = worksite();
        String deviceOne = device(ownerId);
        String deviceTwo = device(ownerId);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(ownerId);
        when(currentUser.allowedObraIds(ownerId)).thenReturn(Optional.of(Set.of(obraId)));

        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger domainWrites = new AtomicInteger();
        AtomicBoolean retryObservedPending = new AtomicBoolean();
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        SyncOperationHandler handler = new SyncOperationHandler() {
            @Override
            public String entityType() {
                return "RDO";
            }

            @Override
            public Set<String> operations() {
                return Set.of("CRIAR_RDO");
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
                int attempt = attempts.incrementAndGet();
                if (failOnce && attempt == 1) {
                    throw new IllegalStateException("falha transitória de teste");
                }
                if (failOnce) {
                    retryObservedPending.set("PENDENTE".equals(jdbc.queryForObject(
                            "SELECT status FROM sync_mutacao_cliente "
                                    + "WHERE proprietario_id = ? AND client_mutation_id = ?",
                            String.class,
                            ownerId,
                            mutation.clientMutationId()
                    )));
                }
                domainWrites.incrementAndGet();
                String appliedEntityId = appliedEntityOverride == null
                        ? (mutation.entityId() == null
                                ? mutation.entidadeId()
                                : mutation.entityId())
                        : appliedEntityOverride;
                memory.registrarEvento(
                        "RDO",
                        appliedEntityId,
                        "RDO_CRIADO",
                        "POSTGRESQL_IT",
                        obraId,
                        Map.of("value", mutation.payload().path("value").asText())
                );
                ObjectNode result = mapper.createObjectNode();
                result.put("id", appliedEntityId);
                return new AppliedSyncMutation("RDO", appliedEntityId, result);
            }
        };
        SyncService service = new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );
        return new Setup(
                ownerId,
                obraId,
                deviceOne,
                deviceTwo,
                service,
                attempts,
                domainWrites,
                retryObservedPending
        );
    }

    private SyncPushRequest.MutacaoCliente canonicalMutation(
            Setup setup,
            String traceDevice,
            String clientId,
            String entityId,
            String value
    ) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", entityId);
        payload.put("obraId", setup.obraId());
        payload.put("value", value);
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
                traceDevice,
                setup.ownerId(),
                setup.obraId(),
                "RDO",
                entityId,
                "CREATE",
                null,
                List.of("id", "obraId", "value"),
                OCCURRED_AT,
                new SyncPushRequest.MutationTrace(
                        setup.ownerId(),
                        traceDevice,
                        List.of(setup.obraId()),
                        correlationId,
                        null,
                        UUID.randomUUID().toString(),
                        hash(payload)
                ),
                new SyncPushRequest.FieldPatch(payload, mapper.createObjectNode()),
                List.of(),
                List.of()
        );
    }

    private SyncPushRequest.MutacaoCliente legacyMutation(
            Setup setup,
            String clientId,
            String entityId,
            String value
    ) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", entityId);
        payload.put("obraId", setup.obraId());
        payload.put("value", value);
        return new SyncPushRequest.MutacaoCliente(
                clientId,
                "RDO",
                entityId,
                "CRIAR_RDO",
                null,
                payload,
                LocalDateTime.parse("2026-07-21T12:00:00"),
                clientId
        );
    }

    private SyncPushRequest.MutacaoCliente copyCanonical(
            SyncPushRequest.MutacaoCliente original,
            ObjectNode payload,
            String entityId,
            String payloadHash,
            List<String> changedFields
    ) {
        return new SyncPushRequest.MutacaoCliente(
                original.clientMutationId(), original.entidadeTipo(), entityId,
                original.operacao(), original.baseVersao(), payload,
                original.criadaNoClienteEm(), original.correlacaoId(), original.schemaVersion(),
                original.deviceId(), original.userId(), original.obraId(), original.entityType(),
                entityId, original.operation(), original.baseVersion(), changedFields,
                original.occurredAt(),
                new SyncPushRequest.MutationTrace(
                        original.trace().actorId(),
                        original.trace().deviceId(),
                        original.trace().authorizationScope(),
                        original.trace().correlationId(),
                        original.trace().causationId(),
                        original.trace().ontologyEventId(),
                        payloadHash
                ),
                new SyncPushRequest.FieldPatch(payload, original.fieldPatch().baseValues()),
                original.relatedEntities(), original.dependsOnMutationIds()
        );
    }

    private SyncPushRequest.MutacaoCliente copyCanonicalWithPatch(
            SyncPushRequest.MutacaoCliente original,
            ObjectNode payload,
            String payloadHash,
            SyncPushRequest.FieldPatch fieldPatch
    ) {
        return new SyncPushRequest.MutacaoCliente(
                original.clientMutationId(), original.entidadeTipo(), original.entidadeId(),
                original.operacao(), original.baseVersao(), payload,
                original.criadaNoClienteEm(), original.correlacaoId(), original.schemaVersion(),
                original.deviceId(), original.userId(), original.obraId(), original.entityType(),
                original.entityId(), original.operation(), original.baseVersion(),
                original.changedFields(), original.occurredAt(),
                new SyncPushRequest.MutationTrace(
                        original.trace().actorId(),
                        original.trace().deviceId(),
                        original.trace().authorizationScope(),
                        original.trace().correlationId(),
                        original.trace().causationId(),
                        original.trace().ontologyEventId(),
                        payloadHash
                ),
                fieldPatch,
                original.relatedEntities(), original.dependsOnMutationIds()
        );
    }

    private void assertRejected(SyncPushResponse response, String category) {
        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("REJEITADA");
            assertThat(result.resultado().path("rejeicao").path("categoria").asText())
                    .isEqualTo(category);
        });
    }

    private String hash(ObjectNode payload) throws Exception {
        String canonical = canonicalJson(payload);
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String canonicalJson(JsonNode value) throws Exception {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(item -> {
                try {
                    values.add(canonicalJson(item));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            return "[" + String.join(",", values) + "]";
        }
        if (value.isObject()) {
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            List<String> entries = new ArrayList<>();
            for (String field : fields) {
                entries.add(mapper.writeValueAsString(field)
                        + ":" + canonicalJson(value.get(field)));
            }
            return "{" + String.join(",", entries) + "}";
        }
        return mapper.writeValueAsString(value);
    }

    private String collaborator() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'canonical-it', 'colaborador', ?,
                          'Operador canônico', 'BETA', TRUE)
                """,
                id,
                id
        );
        return id;
    }

    private String worksite() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra canônica')",
                id,
                "CAN-" + id
        );
        return id;
    }

    private String device(String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, usuario_id, ativo) VALUES (?, ?, TRUE)",
                id,
                ownerId
        );
        jdbc.update("INSERT INTO sync_estado_dispositivo (dispositivo_id) VALUES (?)", id);
        return id;
    }

    private record Setup(
            String ownerId,
            String obraId,
            String deviceOne,
            String deviceTwo,
            SyncService service,
            AtomicInteger attempts,
            AtomicInteger domainWrites,
            AtomicBoolean retryObservedPending
    ) {
    }
}

package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
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

    private Setup setup(boolean failOnce) {
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
                memory.registrarEvento(
                        "RDO",
                        mutation.entityId(),
                        "RDO_CRIADO",
                        "POSTGRESQL_IT",
                        obraId,
                        Map.of("value", mutation.payload().path("value").asText())
                );
                ObjectNode result = mapper.createObjectNode();
                result.put("id", mutation.entityId());
                return new AppliedSyncMutation("RDO", mutation.entityId(), result);
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

    private String hash(ObjectNode payload) throws Exception {
        String canonical = "{\"id\":" + mapper.writeValueAsString(payload.path("id"))
                + ",\"obraId\":" + mapper.writeValueAsString(payload.path("obraId"))
                + ",\"value\":" + mapper.writeValueAsString(payload.path("value")) + "}";
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
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

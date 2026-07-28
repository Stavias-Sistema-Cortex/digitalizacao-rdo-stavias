package com.projeto.cortex.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.ontology.graph.GraphProjectionAfterCommitListener;
import com.projeto.cortex.ontology.graph.GraphProjectionCatchUpService;
import com.projeto.cortex.ontology.graph.GraphProjectionRecoveryScheduler;
import com.projeto.cortex.ontology.graph.GraphProjectionService;
import com.projeto.cortex.ontology.graph.OntologyGraphRepository;
import com.projeto.cortex.ontology.graph.OperationalGraphProjector;
import com.projeto.cortex.ontology.graph.PostgresqlCommittedOperationalEventReader;
import com.projeto.cortex.ontology.graph.PostgresqlOntologyGraphRepository;
import com.projeto.cortex.rdos.RdoAssetEligibilityService;
import com.projeto.cortex.rdos.RdoAttachmentService;
import com.projeto.cortex.rdos.RdoContextResponse;
import com.projeto.cortex.rdos.RdoContextService;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoMemoryPublisher;
import com.projeto.cortex.rdos.RdoOperationalDetailService;
import com.projeto.cortex.rdos.RdoOperationalEventService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import com.projeto.cortex.sync.RdoSyncOperationHandler;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlOfflineGraphFlowIT {

    private static final String USER_ID = "00000000-0000-4000-8000-000000000601";
    private static final String WORKSITE_ID = "00000000-0000-4000-8000-000000000602";
    private static final String RDO_ID = "00000000-0000-4000-8000-000000000603";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_offline_graph_flow_it");

    private static DriverManagerDataSource dataSource;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndStartContext() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        context = new AnnotationConfigApplicationContext(GraphFlowConfiguration.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetProjectionAndLedger() {
        jdbc.execute("""
                TRUNCATE TABLE
                    operational_evidences,
                    operational_states,
                    ontology_events,
                    ontology_relations,
                    ontology_entities,
                    graph_projection_checkpoint,
                    cortex_estado_entidade,
                    cortex_evento_operacional
                RESTART IDENTITY CASCADE
                """);
        jdbc.update(
                "UPDATE cortex_evento_commit_sequence SET ultima_commit_seq = 0 WHERE id = 1"
        );
    }

    @Test
    void commitsAfterTransactionProjectsOnceAndReturnsFreshMemoryOnReplay() {
        CortexOperationalMemoryService memory = context.getBean(
                CortexOperationalMemoryService.class
        );
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("numeroRdo", "RDO-0042");
        payload.put("obraId", WORKSITE_ID);
        payload.put("programacaoId", null);
        payload.put("status", "RASCUNHO");
        payload.put("cpf", "52998224725");
        payload.put("email", "private@fixture.invalid");
        payload.put("token", "secret-token");

        long committed = memory.registrarEventoDetalhado(
                eventId,
                "RDO",
                RDO_ID,
                "RDO_CRIADO",
                "OFFLINE_SYNC_IT",
                WORKSITE_ID,
                RDO_ID,
                null,
                List.of(Map.of("tipo", "OBRA", "id", WORKSITE_ID)),
                "OFFLINE",
                "SYNCED",
                LocalDateTime.parse("2026-07-22T10:00:00"),
                LocalDateTime.parse("2026-07-22T10:00:01"),
                13,
                payload
        );
        long replay = memory.registrarEventoDetalhado(
                eventId,
                "RDO",
                RDO_ID,
                "RDO_CRIADO",
                "OFFLINE_SYNC_IT",
                WORKSITE_ID,
                RDO_ID,
                null,
                List.of(Map.of("tipo", "OBRA", "id", WORKSITE_ID)),
                "OFFLINE",
                "SYNCED",
                LocalDateTime.parse("2026-07-22T10:00:00"),
                LocalDateTime.parse("2026-07-22T10:00:01"),
                13,
                payload
        );

        assertThat(replay).isEqualTo(committed);
        assertThat(committed).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_evento_operacional WHERE id = ?",
                Integer.class,
                eventId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT last_commit_sequence FROM graph_projection_checkpoint "
                        + "WHERE projector_name = 'operational-graph-v1'",
                Long.class
        )).isEqualTo(committed);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ontology_events WHERE source_id = ?",
                Integer.class,
                eventId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT jsonb_exists(payload_json, 'programacaoId') FROM ontology_events "
                        + "WHERE source_id = ?",
                Boolean.class,
                eventId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT jsonb_exists_any(payload_json, ARRAY['cpf','email','token']) "
                        + "FROM ontology_events WHERE source_id = ?",
                Boolean.class,
                eventId
        )).isFalse();

        OperationalMemoryPageResponse page = memoryQuery().search(
                OperationalMemoryScope.alfa(USER_ID),
                OperationalMemoryFilter.empty(),
                20,
                null
        );
        assertThat(page.items())
                .extracting(OperationalMemoryEventResponse::eventId)
                .containsExactly(eventId);
        assertThat(page.coverage().graph())
                .isEqualTo(new OperationalMemoryGraphCoverage(
                        committed,
                        committed,
                        0L,
                        true,
                        null
                ));
    }

    @Test
    void canonicalRdoSyncSurvivesProjectionFailureAndRecoversExactlyOnce()
            throws Exception {
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        SyncFlowSetup setup = syncFlowSetup(mapper);
        SyncPushRequest.MutacaoCliente mutation = canonicalRdoMutation(setup, mapper);
        installProjectionFailureGate();

        SyncPushResponse first;
        try {
            jdbc.update("UPDATE graph_projection_failure_gate SET enabled = TRUE");
            first = setup.service().push(new SyncPushRequest(
                    setup.deviceOne(), List.of(mutation)
            ));
        } finally {
            jdbc.update("UPDATE graph_projection_failure_gate SET enabled = FALSE");
        }
        SyncPushResponse replayFromSecondDevice = setup.service().push(new SyncPushRequest(
                setup.deviceTwo(), List.of(mutation)
        ));

        assertThat(first.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("APLICADA");
            assertThat(result.eventoServidorCommitSeq()).isNotNull();
        });
        long commitSequence = first.resultados().getFirst().eventoServidorCommitSeq();
        assertThat(replayFromSecondDevice.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("APLICADA");
            assertThat(result.eventoServidorCommitSeq()).isEqualTo(commitSequence);
        });
        String serverEventId = jdbc.queryForObject(
                "SELECT id FROM cortex_evento_operacional WHERE commit_seq = ?",
                String.class,
                commitSequence
        );

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rdo WHERE id = ?", Integer.class, RDO_ID
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_mutacao_cliente "
                        + "WHERE proprietario_id = ? AND client_mutation_id = ?",
                Integer.class,
                USER_ID,
                mutation.clientMutationId()
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_evento_operacional WHERE entidade_id = ?",
                Integer.class,
                RDO_ID
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ontology_events WHERE source_id = ?",
                Integer.class,
                serverEventId
        )).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT last_commit_sequence, last_error_code
                FROM graph_projection_checkpoint
                WHERE projector_name = 'operational-graph-v1'
                """))
                .containsEntry("last_commit_sequence", 0L)
                .containsEntry("last_error_code", "GRAPH_PROJECTION_FAILED");

        GraphProjectionRecoveryScheduler recovery = new GraphProjectionRecoveryScheduler(
                context.getBean(GraphProjectionCatchUpService.class),
                true,
                100
        );
        recovery.run(null);
        recovery.run(null);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ontology_events WHERE source_id = ?",
                Integer.class,
                serverEventId
        )).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT last_commit_sequence, last_commit_id, last_error_code
                FROM graph_projection_checkpoint
                WHERE projector_name = 'operational-graph-v1'
                """))
                .containsEntry("last_commit_sequence", commitSequence)
                .containsEntry("last_commit_id", serverEventId)
                .containsEntry("last_error_code", null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_projection_checkpoint "
                        + "WHERE projector_name = 'operational-graph-v1'",
                Integer.class
        )).isOne();

        OperationalMemoryPageResponse fresh = memoryQuery().search(
                OperationalMemoryScope.alfa(USER_ID),
                OperationalMemoryFilter.empty(),
                20,
                null
        );
        assertThat(fresh.items())
                .extracting(OperationalMemoryEventResponse::eventId)
                .containsExactly(serverEventId);
        assertThat(fresh.coverage().graph())
                .isEqualTo(new OperationalMemoryGraphCoverage(
                        commitSequence,
                        commitSequence,
                        0L,
                        true,
                        null
                ));
    }

    @Test
    void reportsLagThenBackfillsSparseLedgerInOrderUnderConcurrentWakeups()
            throws Exception {
        insertCanonicalEvent(10L, "event-sparse-10", "RDO_CRIADO");
        insertCanonicalEvent(30L, "event-sparse-30", "RDO_EDITADO");
        insertCanonicalEvent(50L, "event-sparse-50", "RDO_ENVIADO");
        jdbc.update(
                "UPDATE cortex_evento_commit_sequence SET ultima_commit_seq = 50 WHERE id = 1"
        );

        OperationalMemoryPageResponse lagging = memoryQuery().search(
                OperationalMemoryScope.alfa(USER_ID),
                OperationalMemoryFilter.empty(),
                20,
                null
        );
        assertThat(lagging.coverage().graph())
                .isEqualTo(new OperationalMemoryGraphCoverage(
                        0L,
                        50L,
                        3L,
                        false,
                        null
                ));

        GraphProjectionCatchUpService catchUp = context.getBean(
                GraphProjectionCatchUpService.class
        );
        var executor = Executors.newFixedThreadPool(3);
        try {
            List<Callable<GraphProjectionCatchUpService.ProjectionRun>> calls = List.of(
                    () -> catchUp.projectCommitted(50L),
                    () -> catchUp.projectCommitted(50L),
                    () -> catchUp.projectCommitted(50L)
            );
            executor.invokeAll(calls).forEach(future -> {
                try {
                    assertThat(future.get().reachedTarget()).isTrue();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT last_commit_sequence FROM graph_projection_checkpoint "
                        + "WHERE projector_name = 'operational-graph-v1'",
                Long.class
        )).isEqualTo(50L);
        assertThat(jdbc.queryForList(
                "SELECT source_id FROM ontology_events ORDER BY occurred_at, source_id",
                String.class
        )).containsExactly("event-sparse-10", "event-sparse-30", "event-sparse-50");
        assertThat(catchUp.projectCommitted(50L).attemptedEvents()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ontology_events",
                Integer.class
        )).isEqualTo(3);

        OperationalMemoryPageResponse fresh = memoryQuery().search(
                OperationalMemoryScope.alfa(USER_ID),
                OperationalMemoryFilter.empty(),
                20,
                null
        );
        assertThat(fresh.coverage().graph().fresh()).isTrue();
        assertThat(fresh.coverage().graph().lagEventCount()).isZero();
    }

    @Test
    void startupRecoveryProjectsAnEventCommittedWithoutAnAfterCommitWakeup() {
        insertCanonicalEvent(7L, "event-crash-window", "RDO_CRIADO");
        jdbc.update(
                "UPDATE cortex_evento_commit_sequence SET ultima_commit_seq = 7 WHERE id = 1"
        );
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_projection_checkpoint",
                Integer.class
        )).isZero();

        GraphProjectionRecoveryScheduler recovery = new GraphProjectionRecoveryScheduler(
                context.getBean(GraphProjectionCatchUpService.class),
                true,
                100
        );
        recovery.run(null);

        assertThat(jdbc.queryForObject(
                "SELECT last_commit_sequence FROM graph_projection_checkpoint "
                        + "WHERE projector_name = 'operational-graph-v1'",
                Long.class
        )).isEqualTo(7L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ontology_events WHERE source_id = 'event-crash-window'",
                Integer.class
        )).isOne();
    }

    private OperationalMemoryQueryService memoryQuery() {
        return new OperationalMemoryQueryService(
                jdbc,
                new OperationalMemoryCursorSigner(new OperationalMemoryCursorKeyring(
                        "it",
                        "offline-graph-flow-cursor-secret-material"
                                .getBytes(StandardCharsets.UTF_8),
                        null,
                        null
                ))
        );
    }

    private SyncFlowSetup syncFlowSetup(ObjectMapper mapper) {
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'offline-sync-it', 'colaborador', ?,
                          'Operador offline', 'ALFA', TRUE)
                ON CONFLICT (id) DO NOTHING
                """, USER_ID, USER_ID);
        jdbc.update("""
                INSERT INTO obra (id, codigo_contrato, nome)
                VALUES (?, 'OFFLINE-SYNC-IT', 'Obra offline')
                ON CONFLICT (id) DO NOTHING
                """, WORKSITE_ID);
        String deviceOne = "00000000-0000-4000-8000-000000000611";
        String deviceTwo = "00000000-0000-4000-8000-000000000612";
        for (String deviceId : List.of(deviceOne, deviceTwo)) {
            jdbc.update("""
                    INSERT INTO sync_dispositivo (id, usuario_id, ativo)
                    VALUES (?, ?, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """, deviceId, USER_ID);
            jdbc.update("""
                    INSERT INTO sync_estado_dispositivo (dispositivo_id)
                    VALUES (?)
                    ON CONFLICT (dispositivo_id) DO NOTHING
                    """, deviceId);
        }

        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(currentUser.allowedObraIds(USER_ID))
                .thenReturn(java.util.Optional.of(java.util.Set.of(WORKSITE_ID)));
        CortexOperationalMemoryService memory = context.getBean(
                CortexOperationalMemoryService.class
        );
        RdoOperationalDetailService details = new RdoOperationalDetailService(jdbc, memory);
        RdoAttachmentService attachments = new RdoAttachmentService(jdbc, mapper);
        RdoQueryService query = new RdoQueryService(jdbc, details, attachments);
        RdoMemoryPublisher memoryPublisher = new RdoMemoryPublisher(memory, jdbc);
        PrevisaoFinanceiraService finance = mock(PrevisaoFinanceiraService.class);
        RdoService rdoService = new RdoService(
                jdbc,
                mapper,
                currentUser,
                new RdoAssetEligibilityService(jdbc),
                memoryPublisher,
                details,
                attachments,
                new RdoOperationalEventService(memory),
                finance,
                query,
                mock(ObraOperabilityGuard.class)
        );
        RdoSyncOperationHandler handler = new RdoSyncOperationHandler(
                jdbc,
                mapper,
                rdoService,
                mock(RdoDraftUpdateService.class),
                mock(RdoWorkflowService.class),
                query,
                currentUser
        );
        SyncService service = new SyncService(
                jdbc,
                mapper,
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)),
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );
        RdoContextResponse contextReceipt = new RdoContextService(jdbc, mapper)
                .buscarContexto(WORKSITE_ID, LocalDate.of(2026, 7, 22));
        return new SyncFlowSetup(
                service,
                deviceOne,
                deviceTwo,
                contextReceipt.provenance().receiptVersion()
        );
    }

    private SyncPushRequest.MutacaoCliente canonicalRdoMutation(
            SyncFlowSetup setup,
            ObjectMapper mapper
    ) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("creationContextVersion", setup.creationContextVersion());
        payload.put("dataRdo", "2026-07-22");
        payload.put("id", RDO_ID);
        payload.put("obraId", WORKSITE_ID);
        String clientMutationId = "00000000-0000-4000-8000-000000000621";
        String correlationId = "00000000-0000-4000-8000-000000000622";
        return new SyncPushRequest.MutacaoCliente(
                clientMutationId,
                "RDO",
                RDO_ID,
                "CRIAR_RDO",
                null,
                payload,
                LocalDateTime.parse("2026-07-22T10:00:00"),
                correlationId,
                13,
                setup.deviceOne(),
                USER_ID,
                WORKSITE_ID,
                "RDO",
                RDO_ID,
                "CREATE",
                null,
                List.of("creationContextVersion", "dataRdo", "id", "obraId"),
                "2026-07-22T10:00:00.000Z",
                new SyncPushRequest.MutationTrace(
                        USER_ID,
                        setup.deviceOne(),
                        List.of(WORKSITE_ID),
                        correlationId,
                        null,
                        "00000000-0000-4000-8000-000000000623",
                        sha256(mapper.writeValueAsString(payload))
                ),
                new SyncPushRequest.FieldPatch(
                        payload.deepCopy(),
                        mapper.createObjectNode()
                ),
                List.of(),
                List.of()
        );
    }

    private void installProjectionFailureGate() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS graph_projection_failure_gate (
                    enabled boolean NOT NULL
                )
                """);
        jdbc.update("DELETE FROM graph_projection_failure_gate");
        jdbc.update("INSERT INTO graph_projection_failure_gate (enabled) VALUES (FALSE)");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_graph_projection_for_it()
                RETURNS trigger AS $$
                BEGIN
                    IF EXISTS (
                        SELECT 1 FROM graph_projection_failure_gate WHERE enabled
                    ) THEN
                        RAISE EXCEPTION 'forced graph projection failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS graph_projection_failure_it ON ontology_events");
        jdbc.execute("""
                CREATE TRIGGER graph_projection_failure_it
                BEFORE INSERT ON ontology_events
                FOR EACH ROW EXECUTE FUNCTION fail_graph_projection_for_it()
                """);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private void insertCanonicalEvent(long commitSequence, String eventId, String eventType) {
        jdbc.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status,
                    entidades_relacionadas_json, schema_version, payload_json,
                    ocorrido_em
                ) VALUES (
                    ?, ?, 'RDO', ?, ?, ?, ?, 'OFFLINE_SYNC_IT', 'OFFLINE', 'SYNCED',
                    ?::jsonb, 13, ?::jsonb, ?
                )
                """,
                eventId,
                commitSequence,
                RDO_ID,
                WORKSITE_ID,
                RDO_ID,
                eventType,
                "[{\"tipo\":\"OBRA\",\"id\":\"" + WORKSITE_ID + "\"}]",
                "{\"numeroRdo\":\"RDO-0042\",\"programacaoId\":null}",
                LocalDateTime.parse("2026-07-22T10:00:00").plusSeconds(commitSequence)
        );
    }

    private record SyncFlowSetup(
            SyncService service,
            String deviceOne,
            String deviceTwo,
            long creationContextVersion
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class GraphFlowConfiguration {

        @Bean
        DataSource dataSource() {
            return PostgresqlOfflineGraphFlowIT.dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        CortexOperationalMemoryService operationalMemory(
                JdbcTemplate jdbcTemplate,
                ObjectMapper objectMapper,
                ApplicationEventPublisher publisher
        ) {
            return new CortexOperationalMemoryService(
                    jdbcTemplate,
                    objectMapper,
                    publisher
            );
        }

        @Bean
        OperationalGraphProjector graphProjector() {
            return new OperationalGraphProjector();
        }

        @Bean
        PostgresqlOntologyGraphRepository graphRepository(
                JdbcTemplate jdbcTemplate,
                ObjectMapper objectMapper,
                PlatformTransactionManager transactionManager
        ) {
            return new PostgresqlOntologyGraphRepository(
                    jdbcTemplate,
                    objectMapper,
                    transactionManager
            );
        }

        @Bean
        PostgresqlCommittedOperationalEventReader committedEventReader(
                JdbcTemplate jdbcTemplate,
                ObjectMapper objectMapper
        ) {
            return new PostgresqlCommittedOperationalEventReader(
                    jdbcTemplate,
                    objectMapper
            );
        }

        @Bean
        GraphProjectionService graphProjectionService(
                OperationalGraphProjector projector,
                PostgresqlOntologyGraphRepository repository
        ) {
            return new GraphProjectionService(projector, repository);
        }

        @Bean
        GraphProjectionCatchUpService graphCatchUp(
                PostgresqlCommittedOperationalEventReader reader,
                GraphProjectionService projectionService,
                PostgresqlOntologyGraphRepository repository
        ) {
            return new GraphProjectionCatchUpService(
                    reader,
                    projectionService,
                    repository
            );
        }

        @Bean
        GraphProjectionAfterCommitListener graphAfterCommit(
                PostgresqlCommittedOperationalEventReader reader,
                GraphProjectionCatchUpService catchUpService
        ) {
            return new GraphProjectionAfterCommitListener(reader, catchUpService);
        }
    }
}

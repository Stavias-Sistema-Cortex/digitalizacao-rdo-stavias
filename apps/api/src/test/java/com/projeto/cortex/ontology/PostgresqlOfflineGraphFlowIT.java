package com.projeto.cortex.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.ontology.graph.GraphProjectionAfterCommitListener;
import com.projeto.cortex.ontology.graph.GraphProjectionCatchUpService;
import com.projeto.cortex.ontology.graph.GraphProjectionRecoveryScheduler;
import com.projeto.cortex.ontology.graph.GraphProjectionService;
import com.projeto.cortex.ontology.graph.OntologyGraphRepository;
import com.projeto.cortex.ontology.graph.OperationalGraphProjector;
import com.projeto.cortex.ontology.graph.PostgresqlCommittedOperationalEventReader;
import com.projeto.cortex.ontology.graph.PostgresqlOntologyGraphRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

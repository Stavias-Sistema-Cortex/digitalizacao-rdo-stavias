package com.projeto.cortex.ontology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlOntologyGraphRepositoryIT {

    @Test
    void replayKeepsGraphRowsUniqueAndCheckpointAtFortyTwo() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);
            GraphProjectionBatch batch = new OperationalGraphProjector().project(
                    OperationalGraphProjectorTest.executedServiceEvent(42L)
            );
            OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, batch.commitSequence(), batch.commitId()
            );

            repository.upsert(batch);
            Map<String, Integer> firstCounts = graphCounts(jdbc);
            repository.upsert(batch);

            assertThat(graphCounts(jdbc)).isEqualTo(firstCounts);
            assertThat(firstCounts)
                    .containsEntry("entities", 6)
                    .containsEntry("relations", 5)
                    .containsEntry("events", 1)
                    .containsEntry("evidences", 1);
            assertThat(repository.currentCheckpoint()).hasValue(42L);
            assertThat(jdbc.queryForMap("""
                    SELECT last_commit_sequence, last_commit_id, last_error_code
                    FROM graph_projection_checkpoint
                    WHERE projector_name = 'operational-graph-v1'
                    """))
                    .containsEntry("last_commit_sequence", 42L)
                    .containsEntry("last_commit_id", "event-42")
                    .containsEntry("last_error_code", null);
        }
    }

    @Test
    void failedBatchRollsBackRowsAndStoresOnlyABoundedSafeCodeSeparately() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);
            Instant occurredAt = Instant.parse("2026-07-21T12:00:00Z");
            GraphEntity entity = new GraphEntity(
                    "895f094d-b57a-3d3f-baa3-2031a922fb60",
                    "RDO",
                    "rdo",
                    "rdo-failing",
                    "rdo-failing",
                    null,
                    "ACTIVE",
                    Map.of(),
                    occurredAt,
                    occurredAt
            );
            GraphRelation invalidRelation = new GraphRelation(
                    "406df469-3fdc-35b7-b171-41b349f3999d",
                    entity.id(),
                    "BELONGS_TO_WORKSITE",
                    "02a11290-3af7-3bb0-a44a-4e2184c1ece4",
                    BigDecimal.ONE,
                    Map.of(),
                    occurredAt
            );
            GraphProjectionBatch invalidBatch = new GraphProjectionBatch(
                    43L,
                    "event-43",
                    List.of(entity),
                    List.of(invalidRelation),
                    List.of(),
                    List.of(),
                    List.of()
            );
            OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, invalidBatch.commitSequence(), invalidBatch.commitId()
            );

            assertThatThrownBy(() -> repository.upsert(invalidBatch))
                    .isInstanceOf(RuntimeException.class);
            repository.markProjectionFailure(
                    43L,
                    "unsafe sql detail: select * from secrets; " + "x".repeat(200)
            );

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ontology_entities",
                    Integer.class
            )).isZero();
            assertThat(repository.currentCheckpoint()).hasValue(0L);
            Map<String, Object> checkpoint = jdbc.queryForMap("""
                    SELECT last_commit_sequence, last_commit_id, last_error_code,
                           length(last_error_code) AS error_length
                    FROM graph_projection_checkpoint
                    WHERE projector_name = 'operational-graph-v1'
                    """);
            assertThat(checkpoint)
                    .containsEntry("last_commit_sequence", 0L)
                    .containsEntry("last_commit_id", null)
                    .containsEntry("last_error_code", "GRAPH_PROJECTION_FAILED")
                    .containsEntry("error_length", 23);
        }
    }

    @Test
    void sparseReferenceDoesNotDowngradePreviouslyProjectedEntityAttributes() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);
            OperationalGraphProjector projector = new OperationalGraphProjector();
            Instant occurredAt = Instant.parse("2026-07-21T11:00:00Z");

            GraphProjectionBatch first = projector.project(new CommittedOperationalEvent(
                    10L,
                    "event-rdo-created",
                    "RDO_CREATED",
                    new CommittedOperationalEvent.EntityRef("RDO", "rdo-7"),
                    List.of(new CommittedOperationalEvent.EntityRef("WORKSITE", "obra-1")),
                    occurredAt,
                    Map.of(
                            "number", "RDO-007",
                            "description", "free-form text must not be projected",
                            "status", "ACTIVE",
                            "worksiteId", "obra-1"
                    )
            ));
            GraphProjectionBatch second = projector.project(
                    OperationalGraphProjectorTest.executedServiceEvent(11L)
            );
            OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, first.commitSequence(), first.commitId()
            );
            OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, second.commitSequence(), second.commitId()
            );
            repository.upsert(first);
            repository.upsert(second);

            assertThat(jdbc.queryForMap("""
                    SELECT canonical_name, description, status
                    FROM ontology_entities
                    WHERE external_ref_type = 'rdo' AND external_ref_id = 'rdo-7'
                    """))
                    .containsEntry("canonical_name", "RDO-007")
                    .containsEntry("description", null)
                    .containsEntry("status", "ACTIVE");
            assertThat(jdbc.queryForObject("""
                    SELECT metadata_json ->> 'worksiteId'
                    FROM ontology_entities
                    WHERE external_ref_type = 'rdo' AND external_ref_id = 'rdo-7'
                    """, String.class)).isEqualTo("obra-1");
        }
    }

    @Test
    void insertsDelayedStatesBetweenPredecessorAndSuccessorWithoutOverlap() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);
            OperationalGraphProjector projector = new OperationalGraphProjector();
            List<GraphProjectionBatch> batches = List.of(
                    projector.project(statusEvent(
                            10L, "event-active", "2026-07-22T12:00:00Z", "ACTIVE"
                    )),
                    projector.project(statusEvent(
                            20L, "event-done", "2026-07-22T14:00:00Z", "DONE"
                    )),
                    projector.project(statusEvent(
                            30L, "event-paused-delayed", "2026-07-22T13:00:00Z", "PAUSED"
                    ))
            );
            batches.forEach(batch -> OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, batch.commitSequence(), batch.commitId()
            ));

            batches.forEach(repository::upsert);
            repository.upsert(batches.get(2));

            List<StateInterval> timeline = jdbc.query("""
                    SELECT state_value, valid_from, valid_to
                    FROM operational_states
                    WHERE state_type = 'STATUS'
                    ORDER BY valid_from, id
                    """, (resultSet, rowNumber) -> new StateInterval(
                    resultSet.getString("state_value"),
                    resultSet.getTimestamp("valid_from").toInstant(),
                    toInstant(resultSet.getTimestamp("valid_to"))
            ));
            assertThat(timeline).containsExactly(
                    new StateInterval(
                            "ACTIVE",
                            Instant.parse("2026-07-22T12:00:00Z"),
                            Instant.parse("2026-07-22T13:00:00Z")
                    ),
                    new StateInterval(
                            "PAUSED",
                            Instant.parse("2026-07-22T13:00:00Z"),
                            Instant.parse("2026-07-22T14:00:00Z")
                    ),
                    new StateInterval(
                            "DONE",
                            Instant.parse("2026-07-22T14:00:00Z"),
                            null
                    )
            );
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM operational_states
                    WHERE valid_to IS NULL
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM operational_states
                    WHERE valid_to < valid_from
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM (
                        SELECT valid_to,
                               LEAD(valid_from) OVER (ORDER BY valid_from, id) AS next_valid_from
                        FROM operational_states
                        WHERE state_type = 'STATUS'
                    ) timeline
                    WHERE valid_to > next_valid_from
                    """, Integer.class)).isZero();
        }
    }

    @Test
    void queryProjectionNeverReturnsFreeFormDescriptionForRdoWorksiteOrService() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);
            OperationalGraphProjector projector = new OperationalGraphProjector();
            String secret = "cpf=52998224725 authorization=Bearer-secret";
            List<CommittedOperationalEvent> sourceEvents = List.of(
                    adversarialEvent(
                            10L, "RDO_CRIADO", "RDO", "rdo-safe",
                            List.of(new CommittedOperationalEvent.EntityRef(
                                    "OBRA", "obra-safe"
                            )),
                            Map.of("numeroRdo", "RDO-0042", "description", secret)
                    ),
                    adversarialEvent(
                            20L, "OBRA_ATUALIZADA", "OBRA", "obra-safe", List.of(),
                            Map.of("obraName", "Obra Segura", "description", secret)
                    ),
                    adversarialEvent(
                            30L, "SERVICO_ATUALIZADO", "SERVICO", "service-safe",
                            List.of(new CommittedOperationalEvent.EntityRef(
                                    "OBRA", "obra-safe"
                            )),
                            Map.of("servicoNome", "Pavimentacao", "description", secret)
                    )
            );
            List<GraphProjectionBatch> batches = sourceEvents.stream()
                    .map(projector::project)
                    .toList();
            batches.forEach(batch -> OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, batch.commitSequence(), batch.commitId()
            ));
            batches.forEach(repository::upsert);

            OntologyGraphQueryService queryService = new OntologyGraphQueryService(
                    jdbc, new ObjectMapper()
            );
            List<GraphEntity> entities = queryService.listEntitiesScoped(
                    java.util.Set.of("obra-safe"), null, null, 0, 100
            );
            List<GraphEvent> events = queryService.listEventsScoped(
                    java.util.Set.of("obra-safe"), null, null, 0, 100
            );

            assertThat(entities)
                    .extracting(GraphEntity::canonicalName)
                    .containsExactlyInAnyOrder("RDO-0042", "Obra Segura", "Pavimentacao");
            assertThat(entities).allSatisfy(entity -> {
                assertThat(entity.description()).isNull();
                assertThat(entity.toString()).doesNotContain(secret);
            });
            assertThat(events).extracting(GraphEvent::description)
                    .containsExactlyInAnyOrder(
                            "RDO_CRIADO", "OBRA_ATUALIZADA", "SERVICO_ATUALIZADO"
                    );
            assertThat(events).allSatisfy(event -> {
                assertThat(event.payload()).doesNotContainKey("description");
                assertThat(event.toString()).doesNotContain(secret);
            });
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM ontology_events
                    WHERE payload_json ? 'description'
                       OR description LIKE '%Bearer-secret%'
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM ontology_entities
                    WHERE description LIKE '%Bearer-secret%'
                       OR metadata_json::text LIKE '%Bearer-secret%'
                    """, Integer.class)).isZero();
        }
    }

    @Test
    void olderFailureCannotRestoreAnErrorAfterANewerCheckpointSucceeded() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);

            GraphProjectionBatch batch = new OperationalGraphProjector().project(
                    OperationalGraphProjectorTest.executedServiceEvent(44L)
            );
            OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, batch.commitSequence(), batch.commitId()
            );
            repository.upsert(batch);
            repository.markProjectionFailure(43L, "OLDER_PROJECTION_FAILED");

            assertThat(jdbc.queryForMap("""
                    SELECT last_commit_sequence, last_commit_id, last_error_code
                    FROM graph_projection_checkpoint
                    WHERE projector_name = 'operational-graph-v1'
                    """))
                    .containsEntry("last_commit_sequence", 44L)
                    .containsEntry("last_commit_id", "event-44")
                    .containsEntry("last_error_code", null);
        }
    }

    @Test
    void refusesToSkipTheSmallestCanonicalSourceCommitEvenWhenSparse() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);
            OperationalGraphProjector projector = new OperationalGraphProjector();
            GraphProjectionBatch first = projector.project(
                    OperationalGraphProjectorTest.executedServiceEvent(10L)
            );
            GraphProjectionBatch later = projector.project(
                    OperationalGraphProjectorTest.executedServiceEvent(30L)
            );
            OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, first.commitSequence(), first.commitId()
            );
            OntologyGraphPostgresqlTestSupport.insertCanonicalSource(
                    jdbc, later.commitSequence(), later.commitId()
            );
            GraphProjectionService service = new GraphProjectionService(
                    projector,
                    repository
            );

            assertThatThrownBy(() -> service.project(
                    OperationalGraphProjectorTest.executedServiceEvent(30L)
            )).isInstanceOfSatisfying(
                    GraphProjectionService.GraphProjectionException.class,
                    error -> assertThat(error.safeCode())
                            .isEqualTo("GRAPH_PROJECTION_ORDER_GAP")
            );
            assertThat(repository.currentCheckpoint()).hasValue(0L);

            repository.upsert(first);
            repository.upsert(later);
            assertThat(repository.currentCheckpoint()).hasValue(30L);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ontology_events",
                    Integer.class
            )).isEqualTo(2);
        }
    }

    private static PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex");
    }

    private static JdbcTemplate migratedJdbc(PostgreSQLContainer<?> database) {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()
        ));
    }

    private static PostgresqlOntologyGraphRepository repository(JdbcTemplate jdbc) {
        return new PostgresqlOntologyGraphRepository(
                jdbc,
                new ObjectMapper(),
                new DataSourceTransactionManager(jdbc.getDataSource())
        );
    }

    private static CommittedOperationalEvent statusEvent(
            long sequence,
            String commitId,
            String occurredAt,
            String status
    ) {
        return new CommittedOperationalEvent(
                sequence,
                commitId,
                "RDO_STATUS_CHANGED",
                new CommittedOperationalEvent.EntityRef("RDO", "rdo-temporal"),
                List.of(),
                Instant.parse(occurredAt),
                Map.of("status", status)
        );
    }

    private static CommittedOperationalEvent adversarialEvent(
            long sequence,
            String eventType,
            String entityType,
            String entityId,
            List<CommittedOperationalEvent.EntityRef> related,
            Map<String, Object> payload
    ) {
        return new CommittedOperationalEvent(
                sequence,
                "adversarial-event-" + sequence,
                eventType,
                new CommittedOperationalEvent.EntityRef(entityType, entityId),
                related,
                Instant.parse("2026-07-22T12:00:00Z").plusSeconds(sequence),
                payload
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Map<String, Integer> graphCounts(JdbcTemplate jdbc) {
        return Map.of(
                "entities", count(jdbc, "ontology_entities"),
                "relations", count(jdbc, "ontology_relations"),
                "events", count(jdbc, "ontology_events"),
                "states", count(jdbc, "operational_states"),
                "evidences", count(jdbc, "operational_evidences")
        );
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record StateInterval(String value, Instant validFrom, Instant validTo) {
    }
}

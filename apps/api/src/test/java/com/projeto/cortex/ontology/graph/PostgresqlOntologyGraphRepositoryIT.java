package com.projeto.cortex.ontology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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

            repository.upsert(batch);
            Map<String, Integer> firstCounts = graphCounts(jdbc);
            repository.upsert(batch);

            assertThat(graphCounts(jdbc)).isEqualTo(firstCounts);
            assertThat(firstCounts)
                    .containsEntry("entities", 5)
                    .containsEntry("relations", 4)
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

            repository.upsert(projector.project(new CommittedOperationalEvent(
                    10L,
                    "event-rdo-created",
                    "RDO_CREATED",
                    new CommittedOperationalEvent.EntityRef("RDO", "rdo-7"),
                    List.of(new CommittedOperationalEvent.EntityRef("WORKSITE", "obra-1")),
                    occurredAt,
                    Map.of(
                            "number", "RDO-007",
                            "description", "RDO authoritative description",
                            "status", "ACTIVE",
                            "worksiteId", "obra-1"
                    )
            )));
            repository.upsert(projector.project(
                    OperationalGraphProjectorTest.executedServiceEvent(11L)
            ));

            assertThat(jdbc.queryForMap("""
                    SELECT canonical_name, description, status
                    FROM ontology_entities
                    WHERE external_ref_type = 'rdo' AND external_ref_id = 'rdo-7'
                    """))
                    .containsEntry("canonical_name", "RDO-007")
                    .containsEntry("description", "RDO authoritative description")
                    .containsEntry("status", "ACTIVE");
            assertThat(jdbc.queryForObject("""
                    SELECT metadata_json ->> 'worksiteId'
                    FROM ontology_entities
                    WHERE external_ref_type = 'rdo' AND external_ref_id = 'rdo-7'
                    """, String.class)).isEqualTo("obra-1");
        }
    }

    @Test
    void olderFailureCannotRestoreAnErrorAfterANewerCheckpointSucceeded() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = repository(jdbc);

            repository.upsert(new OperationalGraphProjector().project(
                    OperationalGraphProjectorTest.executedServiceEvent(44L)
            ));
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
}

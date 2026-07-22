package com.projeto.cortex.ontology.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlOntologyGraphQueryServiceIT {

    @Test
    void resolvesWorksiteAndQueriesProjectedJsonbWithBoundedTraversal() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex")) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = new PostgresqlOntologyGraphRepository(
                    jdbc,
                    new ObjectMapper(),
                    new DataSourceTransactionManager(jdbc.getDataSource())
            );
            repository.upsert(new OperationalGraphProjector().project(
                    OperationalGraphProjectorTest.executedServiceEvent(42L)
            ));
            OntologyGraphQueryService queryService = new OntologyGraphQueryService(
                    jdbc,
                    new ObjectMapper()
            );
            String worksiteEntityId = entityId(jdbc, "obra-1");
            String executionEntityId = entityId(jdbc, "execution-9");
            jdbc.update("""
                    INSERT INTO operational_states (
                        id, entity_id, state_type, state_value, valid_from, metadata_json
                    ) VALUES (?, ?, ?, ?, ?::timestamp, ?::jsonb)
                    """,
                    "state-query-it",
                    executionEntityId,
                    "STATUS",
                    "ACCEPTED",
                    "2026-07-21 12:00:00",
                    "{\"obraId\":\"obra-1\"}"
            );

            assertThat(queryService.resolveWorksiteId(worksiteEntityId)).hasValue("obra-1");
            assertThat(queryService.resolveWorksiteId(executionEntityId)).hasValue("obra-1");
            assertThat(queryService.listEntities("obra-1", "RDO_EXECUTION", "execution", 0, 10))
                    .extracting(GraphEntity::externalRefId)
                    .containsExactly("execution-9");
            assertThat(queryService.listRelations(
                    "obra-1",
                    executionEntityId,
                    null,
                    2,
                    0,
                    100
            )).extracting(GraphRelation::type)
                    .contains("BELONGS_TO_WORKSITE", "RECORDED_IN", "EXECUTES_SERVICE", "PRICED_BY");
            assertThat(queryService.listEvents("obra-1", executionEntityId, null, 0, 10))
                    .extracting(GraphEvent::sourceId)
                    .containsExactly("event-42");
            assertThat(queryService.listStates("obra-1", executionEntityId, "STATUS", 0, 10))
                    .extracting(GraphState::value)
                    .containsExactly("ACCEPTED");
            assertThat(queryService.listEvidences("obra-1", executionEntityId, null, 0, 10))
                    .extracting(GraphEvidence::sourceId)
                    .containsExactly("event-42");
        }
    }

    private static JdbcTemplate migratedJdbc(PostgreSQLContainer<?> database) {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword()
        ));
    }

    private static String entityId(JdbcTemplate jdbc, String externalRefId) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM ontology_entities WHERE external_ref_id = ?",
                String.class,
                externalRefId
        );
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }
}

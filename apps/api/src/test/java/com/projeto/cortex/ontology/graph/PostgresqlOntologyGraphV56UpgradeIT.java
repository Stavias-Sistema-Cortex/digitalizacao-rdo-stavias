package com.projeto.cortex.ontology.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlOntologyGraphV56UpgradeIT {

    private static final String WORKSITE_A = "upgrade-worksite-a";
    private static final String WORKSITE_B = "upgrade-worksite-b";
    private static final String UNTRUSTED_WORKSITE = "payload-claimed-worksite";
    private static final String SHARED_ASSET = "upgrade-shared-asset";
    private static final String EVENT_A = "upgrade-canonical-event-a";
    private static final String EVENT_B = "upgrade-canonical-event-b";
    private static final String PROJECTOR_NAME = "operational-graph-v1";

    @Test
    void rebuildsPollutedV55ProjectionFromCanonicalWorksiteProvenance() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("cortex_graph_v56_upgrade_it")) {
            database.start();
            migrate(database, "55");
            JdbcTemplate jdbc = jdbc(database);
            seedCanonicalHistory(jdbc);
            seedPollutedDerivedGraph(jdbc);

            migrate(database, null);

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cortex_evento_operacional",
                    Integer.class
            )).isEqualTo(2);
            assertDerivedGraphWasDiscarded(jdbc);
            assertCheckpointWasReset(jdbc);

            ObjectMapper objectMapper = new ObjectMapper();
            PostgresqlOntologyGraphRepository repository =
                    new PostgresqlOntologyGraphRepository(
                            jdbc,
                            objectMapper,
                            new DataSourceTransactionManager(jdbc.getDataSource())
                    );
            GraphProjectionCatchUpService catchUp = new GraphProjectionCatchUpService(
                    new PostgresqlCommittedOperationalEventReader(jdbc, objectMapper),
                    new GraphProjectionService(
                            new OperationalGraphProjector(),
                            repository
                    ),
                    repository
            );

            GraphProjectionCatchUpService.ProjectionRun run =
                    catchUp.projectAvailable(10);

            assertThat(run.attemptedEvents()).isEqualTo(2);
            assertThat(run.checkpointCommitSequence()).isEqualTo(2);
            assertThat(run.reachedTarget()).isTrue();
            assertThat(repository.currentCheckpoint()).isEqualTo(OptionalLong.of(2));
            assertCanonicalReplayIsWorksiteIsolated(jdbc, objectMapper);
        }
    }

    private static void assertDerivedGraphWasDiscarded(JdbcTemplate jdbc) {
        assertThat(rowCount(jdbc, "ontology_entities")).isZero();
        assertThat(rowCount(jdbc, "ontology_relations")).isZero();
        assertThat(rowCount(jdbc, "ontology_events")).isZero();
        assertThat(rowCount(jdbc, "operational_states")).isZero();
        assertThat(rowCount(jdbc, "operational_evidences")).isZero();
        assertThat(rowCount(jdbc, "operational_decisions")).isZero();
        assertThat(rowCount(jdbc, "ontology_entity_worksite_snapshots")).isZero();
    }

    private static void assertCheckpointWasReset(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM graph_projection_checkpoint
                WHERE projector_name = ?
                  AND (
                      last_commit_sequence <> 0
                      OR last_commit_id IS NOT NULL
                      OR last_error_code IS NOT NULL
                  )
                """,
                Integer.class,
                PROJECTOR_NAME
        )).isZero();
        assertThat(jdbc.queryForList(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success IS true
                ORDER BY installed_rank
                """,
                String.class
        )).contains("55", "56");
    }

    private static void assertCanonicalReplayIsWorksiteIsolated(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        OntologyGraphQueryService queryService =
                new OntologyGraphQueryService(jdbc, objectMapper);
        String sharedEntityId = jdbc.queryForObject(
                """
                SELECT id
                FROM ontology_entities
                WHERE entity_type = 'ASSET'
                  AND external_ref_id = ?
                """,
                String.class,
                SHARED_ASSET
        );
        assertThat(sharedEntityId).isNotBlank();

        GraphEntity viewA = queryService.findEntityScoped(
                sharedEntityId,
                Set.of(WORKSITE_A)
        ).orElseThrow();
        GraphEntity viewB = queryService.findEntityScoped(
                sharedEntityId,
                Set.of(WORKSITE_B)
        ).orElseThrow();

        assertThat(viewA.canonicalName()).isEqualTo("Ativo da obra A");
        assertThat(viewA.status()).isEqualTo("ACTIVE_A");
        assertThat(viewA.metadata())
                .containsEntry("worksiteId", WORKSITE_A)
                .containsEntry("obraId", WORKSITE_A)
                .doesNotContainValue(WORKSITE_B)
                .doesNotContainValue(UNTRUSTED_WORKSITE);
        assertThat(viewB.canonicalName()).isEqualTo("Ativo da obra B");
        assertThat(viewB.status()).isEqualTo("ACTIVE_B");
        assertThat(viewB.metadata())
                .containsEntry("worksiteId", WORKSITE_B)
                .containsEntry("obraId", WORKSITE_B)
                .doesNotContainValue(WORKSITE_A)
                .doesNotContainValue(UNTRUSTED_WORKSITE);

        assertThat(queryService.resolveWorksiteIds(Set.of(sharedEntityId)))
                .containsEntry(sharedEntityId, Set.of(WORKSITE_A, WORKSITE_B));
        assertThat(queryService.listEventsScoped(
                Set.of(WORKSITE_A), sharedEntityId, null, 0, 10
        ))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.sourceId()).isEqualTo(EVENT_A);
                    assertThat(event.payload())
                            .containsEntry("worksiteId", WORKSITE_A)
                            .containsEntry("obraId", WORKSITE_A)
                            .doesNotContainValue(UNTRUSTED_WORKSITE);
                });
        assertThat(queryService.listEventsScoped(
                Set.of(WORKSITE_B), sharedEntityId, null, 0, 10
        ))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.sourceId()).isEqualTo(EVENT_B);
                    assertThat(event.payload())
                            .containsEntry("worksiteId", WORKSITE_B)
                            .containsEntry("obraId", WORKSITE_B)
                            .doesNotContainValue(UNTRUSTED_WORKSITE);
                });
        assertThat(queryService.listStatesScoped(
                Set.of(WORKSITE_A), sharedEntityId, "STATUS", 0, 10
        ))
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.value()).isEqualTo("ACTIVE_A");
                    assertThat(state.metadata())
                            .containsEntry("worksiteId", WORKSITE_A)
                            .containsEntry("obraId", WORKSITE_A);
                });
        assertThat(queryService.listStatesScoped(
                Set.of(WORKSITE_B), sharedEntityId, "STATUS", 0, 10
        ))
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.value()).isEqualTo("ACTIVE_B");
                    assertThat(state.metadata())
                            .containsEntry("worksiteId", WORKSITE_B)
                            .containsEntry("obraId", WORKSITE_B);
                });

        List<String> projectedWorksites = jdbc.queryForList(
                """
                SELECT target.external_ref_id
                FROM ontology_relations relation
                JOIN ontology_entities target
                  ON target.id = relation.target_entity_id
                WHERE relation.source_entity_id = ?
                  AND relation.relation_type = 'BELONGS_TO_WORKSITE'
                ORDER BY target.external_ref_id
                """,
                String.class,
                sharedEntityId
        );
        assertThat(projectedWorksites).containsExactly(WORKSITE_A, WORKSITE_B);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM ontology_entities
                WHERE external_ref_id = ?
                   OR metadata_json::text LIKE '%polluted-derived-only%'
                """,
                Integer.class,
                UNTRUSTED_WORKSITE
        )).isZero();
        assertThat(jdbc.queryForList(
                """
                SELECT source_worksite_id
                FROM ontology_entity_worksite_snapshots
                WHERE entity_id = ?
                ORDER BY source_worksite_id
                """,
                String.class,
                sharedEntityId
        )).containsExactly(WORKSITE_A, WORKSITE_B);
        assertThat(jdbc.queryForList(
                """
                SELECT source_id || ':' || source_worksite_id
                FROM ontology_events
                WHERE entity_id = ?
                ORDER BY source_id
                """,
                String.class,
                sharedEntityId
        )).containsExactly(
                EVENT_A + ":" + WORKSITE_A,
                EVENT_B + ":" + WORKSITE_B
        );
    }

    private static void seedCanonicalHistory(JdbcTemplate jdbc) {
        insertCanonicalEvent(
                jdbc,
                1,
                EVENT_A,
                WORKSITE_A,
                "Ativo da obra A",
                "ACTIVE_A",
                "2026-07-21 12:00:00"
        );
        insertCanonicalEvent(
                jdbc,
                2,
                EVENT_B,
                WORKSITE_B,
                "Ativo da obra B",
                "ACTIVE_B",
                "2026-07-21 12:01:00"
        );
    }

    private static void insertCanonicalEvent(
            JdbcTemplate jdbc,
            long commitSequence,
            String eventId,
            String authoritativeWorksiteId,
            String assetName,
            String status,
            String occurredAt
    ) {
        jdbc.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id,
                    tipo_evento, entidades_relacionadas_json, payload_json,
                    ocorrido_em
                ) VALUES (
                    ?, ?, 'ASSET', ?, ?, 'RDO_ASSET_USED',
                    jsonb_build_array(jsonb_build_object(
                        'tipo', 'WORKSITE',
                        'id', ?
                    )),
                    jsonb_build_object(
                        'worksiteId', ?,
                        'obraId', ?,
                        'assetName', ?,
                        'status', ?
                    ),
                    ?::timestamp
                )
                """,
                eventId,
                commitSequence,
                SHARED_ASSET,
                authoritativeWorksiteId,
                UNTRUSTED_WORKSITE,
                UNTRUSTED_WORKSITE,
                UNTRUSTED_WORKSITE,
                assetName,
                status,
                occurredAt
        );
    }

    private static void seedPollutedDerivedGraph(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id,
                    canonical_name, status, metadata_json
                ) VALUES
                    (
                        'polluted-shared-entity', 'ASSET', 'asset', ?,
                        'Polluted foreign name', 'POLLUTED',
                        jsonb_build_object(
                            'worksiteId', ?,
                            'secret', 'polluted-derived-only'
                        )
                    ),
                    (
                        'polluted-worksite', 'WORKSITE', 'obra', ?,
                        'Polluted foreign worksite', NULL, '{}'::jsonb
                    )
                """,
                SHARED_ASSET,
                UNTRUSTED_WORKSITE,
                UNTRUSTED_WORKSITE
        );
        jdbc.update(
                """
                INSERT INTO ontology_relations (
                    id, source_entity_id, relation_type, target_entity_id
                ) VALUES (
                    'polluted-relation', 'polluted-shared-entity',
                    'BELONGS_TO_WORKSITE', 'polluted-worksite'
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO ontology_events (
                    id, event_type, entity_id, source_type, source_id,
                    description, payload_json
                ) VALUES (
                    'polluted-event', 'RDO_ASSET_USED', 'polluted-shared-entity',
                    'CANONICAL_OPERATIONAL_EVENT', ?,
                    'Polluted derived event',
                    jsonb_build_object(
                        'worksiteId', ?,
                        'secret', 'polluted-derived-only'
                    )
                )
                """,
                EVENT_B,
                UNTRUSTED_WORKSITE
        );
        jdbc.update(
                """
                INSERT INTO operational_states (
                    id, entity_id, state_type, state_value, source_event_id,
                    metadata_json
                ) VALUES (
                    'polluted-state', 'polluted-shared-entity',
                    'STATUS', 'POLLUTED', 'polluted-event',
                    jsonb_build_object(
                        'worksiteId', ?,
                        'secret', 'polluted-derived-only'
                    )
                )
                """,
                UNTRUSTED_WORKSITE
        );
        jdbc.update(
                """
                INSERT INTO operational_evidences (
                    id, entity_id, evidence_type, source_type, source_id,
                    description, metadata_json
                ) VALUES (
                    'polluted-evidence', 'polluted-shared-entity',
                    'BOUNDARY', 'CANONICAL_OPERATIONAL_EVENT', ?,
                    'Polluted derived evidence',
                    jsonb_build_object(
                        'worksiteId', ?,
                        'secret', 'polluted-derived-only'
                    )
                )
                """,
                EVENT_B,
                UNTRUSTED_WORKSITE
        );
        jdbc.update(
                """
                INSERT INTO operational_decisions (
                    id, entity_id, decision_type, description, metadata_json
                ) VALUES (
                    'polluted-decision', 'polluted-shared-entity',
                    'BOUNDARY', 'Polluted derived decision',
                    jsonb_build_object('secret', 'polluted-derived-only')
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO graph_projection_checkpoint (
                    projector_name, last_commit_sequence, last_commit_id,
                    last_error_code
                ) VALUES (?, 2, ?, 'POLLUTED_FAILURE')
                """,
                PROJECTOR_NAME,
                EVENT_B
        );
    }

    private static int rowCount(JdbcTemplate jdbc, String table) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class
        );
        return value == null ? 0 : value;
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword()
        ));
    }

    private static void migrate(
            PostgreSQLContainer<?> database,
            String targetVersion
    ) {
        var configuration = Flyway.configure()
                .dataSource(
                        database.getJdbcUrl(),
                        database.getUsername(),
                        database.getPassword()
                )
                .locations("classpath:db/migration-postgresql");
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        configuration.load().migrate();
    }
}

package com.projeto.cortex.ontology.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostgresqlOntologyGraphQueryServiceIT {

    private static final String WORKSITE_A = "scope-worksite-a";
    private static final String WORKSITE_B = "scope-worksite-b";
    private static final String WORKSITE_C = "scope-worksite-c";

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

            assertThat(queryService.resolveWorksiteIds(Set.of(worksiteEntityId, executionEntityId)))
                    .containsEntry(worksiteEntityId, Set.of("obra-1"))
                    .containsEntry(executionEntityId, Set.of("obra-1"));
            assertThat(queryService.listEntitiesScoped(Set.of("obra-1"), "RDO_EXECUTION", "execution", 0, 10))
                    .extracting(GraphEntity::externalRefId)
                    .containsExactly("execution-9");
            assertThat(queryService.listRelationsScoped(
                    Set.of("obra-1"),
                    executionEntityId,
                    null,
                    2,
                    0,
                    100
            )).extracting(GraphRelation::type)
                    .contains("BELONGS_TO_WORKSITE", "RECORDED_IN", "EXECUTES_SERVICE", "PRICED_BY");
            assertThat(queryService.listEventsScoped(Set.of("obra-1"), executionEntityId, null, 0, 10))
                    .extracting(GraphEvent::sourceId)
                    .containsExactly("event-42");
            assertThat(queryService.listStatesScoped(Set.of("obra-1"), executionEntityId, "STATUS", 0, 10))
                    .extracting(GraphState::value)
                    .containsExactly("ACCEPTED");
            assertThat(queryService.listEvidencesScoped(Set.of("obra-1"), executionEntityId, null, 0, 10))
                    .extracting(GraphEvidence::sourceId)
                    .containsExactly("event-42");
        }
    }

    @Test
    void resolvesDirectedMembershipsAndDepthThreeWithoutFollowingNonProvenanceCycles() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex")) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            OntologyGraphQueryService queryService = queryService(jdbc);
            seedSharedTopology(jdbc);

            Map<String, Set<String>> resolved = queryService.resolveWorksiteIds(Set.of(
                    "shared", "local", "orphan", "service-depth-three"
            ));

            assertThat(resolved).containsEntry(
                    "shared",
                    Set.of(WORKSITE_A, WORKSITE_B)
            );
            assertThat(resolved).containsEntry("local", Set.of(WORKSITE_A));
            assertThat(resolved).containsEntry("orphan", Set.of());
            assertThat(resolved).containsEntry("service-depth-three", Set.of(WORKSITE_C));
        }
    }

    @Test
    void filtersNestedForeignAndOrphanObjectsBeforePaginationAndTreatsSearchLiterally() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex")) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            OntologyGraphQueryService queryService = queryService(jdbc);
            seedSharedTopology(jdbc);
            seedScopedObjects(jdbc);

            assertThat(queryService.listRelationsScoped(
                    Set.of(WORKSITE_A), "shared", "RELATED_TO", 1, 0, 1
            ))
                    .extracting(GraphRelation::id)
                    .containsExactly("relation-local");
            assertThat(queryService.listRelationsScoped(
                    Set.of(WORKSITE_A), "shared", "HIDDEN_LOCAL", 3, 0, 100
            )).isEmpty();
            assertThat(queryService.listEventsScoped(Set.of(WORKSITE_A), "shared", null, 0, 1))
                    .extracting(GraphEvent::id)
                    .containsExactly("event-local");
            assertThat(queryService.listStatesScoped(Set.of(WORKSITE_A), null, "BOUNDARY", 0, 100))
                    .extracting(GraphState::id)
                    .containsExactly("state-local");
            assertThat(queryService.listEvidencesScoped(Set.of(WORKSITE_A), null, "BOUNDARY", 0, 100))
                    .extracting(GraphEvidence::id)
                    .containsExactly("evidence-local");

            assertThat(queryService.listEntitiesScoped(Set.of(WORKSITE_A), null, "%_\\", 0, 100))
                    .extracting(GraphEntity::id)
                    .containsExactly("literal-search");
            assertThat(queryService.listEntitiesScoped(Set.of(WORKSITE_A), null, "%", 0, 100))
                    .extracting(GraphEntity::id)
                    .containsExactly("literal-search");
            assertThat(queryService.listEntitiesScoped(Set.of(WORKSITE_A), null, "ordinary", 0, 100))
                    .isEmpty();
        }
    }

    @Test
    void keepsExclusiveRdoScopeWhenProjectedActorsAndAssetsAreSharedAcrossWorksites()
            throws Exception {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex")) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlOntologyGraphRepository repository = new PostgresqlOntologyGraphRepository(
                    jdbc,
                    new ObjectMapper(),
                    new DataSourceTransactionManager(jdbc.getDataSource())
            );
            OperationalGraphProjector projector = new OperationalGraphProjector();
            projectedSharedActorTopology().stream()
                    .map(projector::project)
                    .forEach(repository::upsert);
            OntologyGraphQueryService queryService = queryService(jdbc);

            String rdoA = entityId(jdbc, "rdo-a");
            String rdoB = entityId(jdbc, "rdo-b");
            String collaborator = entityId(jdbc, "collaborator-shared");
            String asset = entityId(jdbc, "asset-shared");

            assertThat(queryService.resolveWorksiteIds(Set.of(rdoA, rdoB, collaborator, asset)))
                    .containsEntry(rdoA, Set.of(WORKSITE_A))
                    .containsEntry(rdoB, Set.of(WORKSITE_B))
                    .containsEntry(collaborator, Set.of(WORKSITE_A, WORKSITE_B))
                    .containsEntry(asset, Set.of(WORKSITE_A, WORKSITE_B));
            assertThat(queryService.listEntitiesScoped(
                    Set.of(WORKSITE_A), "RDO", null, 0, 100
            )).extracting(GraphEntity::externalRefId)
                    .containsExactly("rdo-a");
            assertThat(queryService.listRelationsScoped(
                    Set.of(WORKSITE_A), collaborator, "PARTICIPATES_IN", 1, 0, 100
            )).extracting(GraphRelation::targetEntityId)
                    .containsExactly(rdoA);
            assertThat(queryService.listRelationsScoped(
                    Set.of(WORKSITE_A), asset, "USED_IN", 1, 0, 100
            )).extracting(GraphRelation::targetEntityId)
                    .containsExactly(rdoA);

            CurrentUserService currentUserService = mock(CurrentUserService.class);
            when(currentUserService.requireUserId()).thenReturn("beta-a");
            when(currentUserService.allowedObraIds("beta-a"))
                    .thenReturn(Optional.of(Set.of(WORKSITE_A)));
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new OntologyGraphController(queryService, currentUserService)
            ).build();
            mockMvc.perform(get("/api/ontology/entities/{id}", rdoB)
                            .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta-a"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ONTOLOGY_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").doesNotExist());
        }
    }

    private static OntologyGraphQueryService queryService(JdbcTemplate jdbc) {
        return new OntologyGraphQueryService(jdbc, new ObjectMapper());
    }

    private static List<CommittedOperationalEvent> projectedSharedActorTopology() {
        return List.of(
                projectedRdo(1L, "rdo-a", WORKSITE_A),
                projectedParticipation(2L, "rdo-a", WORKSITE_A),
                projectedAssetUse(3L, "rdo-a", WORKSITE_A),
                projectedRdo(4L, "rdo-b", WORKSITE_B),
                projectedParticipation(5L, "rdo-b", WORKSITE_B),
                projectedAssetUse(6L, "rdo-b", WORKSITE_B)
        );
    }

    private static CommittedOperationalEvent projectedRdo(
            long sequence,
            String rdoId,
            String worksiteId
    ) {
        return projectedEvent(
                sequence,
                "RDO_CREATED",
                ref("RDO", rdoId),
                List.of(ref("WORKSITE", worksiteId)),
                Map.of("worksiteId", worksiteId, "number", rdoId)
        );
    }

    private static CommittedOperationalEvent projectedParticipation(
            long sequence,
            String rdoId,
            String worksiteId
    ) {
        return projectedEvent(
                sequence,
                "RDO_WORKFORCE_PARTICIPATION_RECORDED",
                ref("COLLABORATOR", "collaborator-shared"),
                List.of(ref("RDO", rdoId), ref("WORKSITE", worksiteId)),
                Map.of("worksiteId", worksiteId, "collaboratorName", "Compartilhado")
        );
    }

    private static CommittedOperationalEvent projectedAssetUse(
            long sequence,
            String rdoId,
            String worksiteId
    ) {
        return projectedEvent(
                sequence,
                "RDO_ASSET_USED",
                ref("ASSET", "asset-shared"),
                List.of(ref("RDO", rdoId), ref("WORKSITE", worksiteId)),
                Map.of("worksiteId", worksiteId, "assetName", "Compartilhado")
        );
    }

    private static CommittedOperationalEvent projectedEvent(
            long sequence,
            String type,
            CommittedOperationalEvent.EntityRef principal,
            List<CommittedOperationalEvent.EntityRef> related,
            Map<String, Object> payload
    ) {
        return new CommittedOperationalEvent(
                sequence,
                "projected-event-" + sequence,
                type,
                principal,
                related,
                Instant.parse("2026-07-21T12:00:00Z").plusSeconds(sequence),
                payload
        );
    }

    private static CommittedOperationalEvent.EntityRef ref(String type, String id) {
        return new CommittedOperationalEvent.EntityRef(type, id);
    }

    private static void seedSharedTopology(JdbcTemplate jdbc) {
        entity(jdbc, "worksite-a", "WORKSITE", WORKSITE_A, "{}", "Obra A");
        entity(jdbc, "worksite-b", "WORKSITE", WORKSITE_B, "{}", "Obra B");
        entity(jdbc, "worksite-c", "WORKSITE", WORKSITE_C, "{}", "Obra C");
        entity(jdbc, "shared", "COLLABORATOR", "shared", jsonWorksite(WORKSITE_B), "Compartilhado");
        entity(jdbc, "local", "RDO", "local", jsonWorksite(WORKSITE_A), "Local");
        entity(jdbc, "foreign", "RDO", "foreign", jsonWorksite(WORKSITE_B), "Estrangeiro");
        entity(jdbc, "orphan", "RDO", "orphan", "{}", "Órfão");
        entity(jdbc, "cycle-1", "ACTIVITY", "cycle-1", "{}", "Ciclo 1");
        entity(jdbc, "cycle-2", "ACTIVITY", "cycle-2", "{}", "Ciclo 2");
        entity(jdbc, "literal-search", "RDO", "literal-search", jsonWorksite(WORKSITE_A), "Literal %_\\");
        entity(jdbc, "service-depth-three", "SERVICE", "service-depth-three", "{}", "Serviço");
        entity(jdbc, "execution-depth-two", "RDO_EXECUTION", "execution-depth-two", "{}", "Execução");
        entity(jdbc, "rdo-depth-one", "RDO", "rdo-depth-one", "{}", "RDO profundo");

        relation(jdbc, "scope-a", "shared", "BELONGS_TO_WORKSITE", "worksite-a", 1);
        relation(jdbc, "scope-b", "shared", "BELONGS_TO_WORKSITE", "worksite-b", 1);
        relation(jdbc, "cycle-edge-1", "shared", "HAS_ACTIVITY", "cycle-1", 1);
        relation(jdbc, "cycle-edge-2", "cycle-1", "HAS_ACTIVITY", "cycle-2", 1);
        relation(jdbc, "cycle-edge-3", "cycle-2", "HAS_ACTIVITY", "shared", 1);
        relation(jdbc, "scope-c", "cycle-2", "BELONGS_TO_WORKSITE", "worksite-c", 1);
        relation(jdbc, "depth-service", "execution-depth-two", "EXECUTES_SERVICE", "service-depth-three", 1);
        relation(jdbc, "depth-execution", "execution-depth-two", "RECORDED_IN", "rdo-depth-one", 1);
        relation(jdbc, "depth-rdo", "rdo-depth-one", "BELONGS_TO_WORKSITE", "worksite-c", 1);
    }

    private static void seedScopedObjects(JdbcTemplate jdbc) {
        entity(jdbc, "hidden-local-1", "RDO", "hidden-local-1", jsonWorksite(WORKSITE_A), "Local oculto 1");
        entity(jdbc, "hidden-local-2", "RDO", "hidden-local-2", jsonWorksite(WORKSITE_A), "Local oculto 2");
        relation(jdbc, "relation-local", "shared", "RELATED_TO", "local", 1);
        relation(jdbc, "relation-orphan", "shared", "RELATED_TO", "orphan", 3);
        relation(jdbc, "relation-foreign", "shared", "RELATED_TO", "foreign", 4);
        relation(jdbc, "foreign-bridge", "foreign", "RELATED_TO", "hidden-local-1", 5);
        relation(jdbc, "hidden-local-edge", "hidden-local-1", "HIDDEN_LOCAL", "hidden-local-2", 6);
        event(jdbc, "event-local", "shared", "local", 1);
        event(jdbc, "event-orphan", "shared", "orphan", 3);
        event(jdbc, "event-foreign", "shared", "foreign", 4);
        state(jdbc, "state-local", "local");
        state(jdbc, "state-foreign", "foreign");
        state(jdbc, "state-orphan", "orphan");
        evidence(jdbc, "evidence-local", "local");
        evidence(jdbc, "evidence-foreign", "foreign");
        evidence(jdbc, "evidence-orphan", "orphan");
    }

    private static void entity(
            JdbcTemplate jdbc,
            String id,
            String type,
            String externalRefId,
            String metadata,
            String name
    ) {
        jdbc.update("""
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id, canonical_name, metadata_json
                ) VALUES (?, ?, 'obra', ?, ?, ?::jsonb)
                """, id, type, externalRefId, name, metadata);
    }

    private static void relation(
            JdbcTemplate jdbc,
            String id,
            String source,
            String type,
            String target,
            int minute
    ) {
        jdbc.update("""
                INSERT INTO ontology_relations (
                    id, source_entity_id, relation_type, target_entity_id, created_at
                ) VALUES (?, ?, ?, ?, timestamp '2026-07-21 12:00:00' + (? * interval '1 minute'))
                """, id, source, type, target, minute);
    }

    private static void event(
            JdbcTemplate jdbc,
            String id,
            String entity,
            String related,
            int minute
    ) {
        jdbc.update("""
                INSERT INTO ontology_events (
                    id, event_type, entity_id, related_entity_id, source_type, source_id,
                    description, occurred_at
                ) VALUES (?, 'BOUNDARY', ?, ?, 'test', ?, 'Evento',
                    timestamp '2026-07-21 12:00:00' + (? * interval '1 minute'))
                """, id, entity, related, id, minute);
    }

    private static void state(JdbcTemplate jdbc, String id, String entity) {
        jdbc.update("""
                INSERT INTO operational_states (id, entity_id, state_type, state_value)
                VALUES (?, ?, 'BOUNDARY', 'VISIBLE')
                """, id, entity);
    }

    private static void evidence(JdbcTemplate jdbc, String id, String entity) {
        jdbc.update("""
                INSERT INTO operational_evidences (
                    id, entity_id, evidence_type, source_type, source_id, description
                ) VALUES (?, ?, 'BOUNDARY', 'test', ?, 'Evidência')
                """, id, entity, id);
    }

    private static String jsonWorksite(String worksiteId) {
        return "{\"obraId\":\"" + worksiteId + "\"}";
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

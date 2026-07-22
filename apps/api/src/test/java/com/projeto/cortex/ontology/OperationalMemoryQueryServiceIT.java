package com.projeto.cortex.ontology;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class OperationalMemoryQueryServiceIT {

    private static final String WORKSITE_A = "00000000-0000-0000-0000-00000000000a";
    private static final String WORKSITE_B = "00000000-0000-0000-0000-00000000000b";
    private static final String RDO_A = "10000000-0000-0000-0000-00000000000a";
    private static final String ACTOR_A = "20000000-0000-0000-0000-00000000000a";
    private static final String ACTOR_B = "20000000-0000-0000-0000-00000000000b";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_operational_memory_it");

    private static JdbcTemplate jdbc;
    private static OperationalMemoryQueryService service;

    @BeforeAll
    static void migrateAndSeed() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        service = new OperationalMemoryQueryService(jdbc);
        seed();
    }

    @Test
    void searchesOnlyWhitelistedOperationalTextWithoutCrossWorksiteOrPiiLeakage() {
        OperationalMemoryScope scope = OperationalMemoryScope.beta(
                ACTOR_A,
                Set.of(WORKSITE_A)
        );

        OperationalMemoryPageResponse drainage = search(scope, "drenagem norte", 20, null);
        assertThat(drainage.items())
                .isNotEmpty()
                .allMatch(item -> WORKSITE_A.equals(item.worksiteId()));
        assertThat(drainage.items()).extracting(OperationalMemoryEventResponse::eventId)
                .doesNotContain(eventId(101));

        OperationalMemoryPageResponse serviceName = search(
                scope,
                "compactacao asfaltica",
                20,
                null
        );
        assertThat(serviceName.items())
                .extracting(OperationalMemoryEventResponse::eventId)
                .containsExactly(eventId(102));
        assertThat(serviceName.items().getFirst().serviceName())
                .isEqualTo("Compactacao asfaltica");

        assertThat(search(scope, "segredo-payload-991", 20, null).items()).isEmpty();
        assertThat(search(scope, "Pessoa Privada B", 20, null).items()).isEmpty();
        assertThat(search(scope, "cpf-52998224725", 20, null).items()).isEmpty();
        assertThat(search(scope, "%_\\", 20, null).items()).isEmpty();

        String indexed = jdbc.queryForObject(
                "SELECT search_text FROM cortex_evento_operacional WHERE id = ?",
                String.class,
                eventId(102)
        );
        assertThat(indexed)
                .contains("compactacao asfaltica")
                .doesNotContain("segredo-payload-991", "cpf-52998224725");
    }

    @Test
    void searchesPermittedIdsAndCanonicalNamesAndReportsComparableRelevance() {
        OperationalMemoryScope scope = OperationalMemoryScope.beta(
                ACTOR_A,
                Set.of(WORKSITE_A)
        );

        OperationalMemoryPageResponse byRdoNumber = search(scope, "RDO-NORTE-77", 20, null);
        assertThat(byRdoNumber.items())
                .extracting(OperationalMemoryEventResponse::eventId)
                .contains(eventId(103));
        assertThat(byRdoNumber.items().getFirst().rdoNumber()).isEqualTo("RDO-NORTE-77");

        OperationalMemoryPageResponse byCanonicalEntity = search(
                scope,
                "Frente Drenagem Principal",
                20,
                null
        );
        assertThat(byCanonicalEntity.items())
                .extracting(OperationalMemoryEventResponse::eventId)
                .contains(eventId(104));
        assertThat(byCanonicalEntity.items().getFirst().principalName())
                .isEqualTo("Frente Drenagem Principal");

        OperationalMemoryPageResponse exactId = search(scope, "entity-drainage-a", 20, null);
        OperationalMemoryPageResponse fuzzyName = search(scope, "drenagem", 20, null);
        assertThat(exactId.items().getFirst().relevance())
                .isGreaterThan(fuzzyName.items().getFirst().relevance());
    }

    @Test
    void paginatesFullAuthorizedHistoryWithStableCommitAndEventPair() {
        OperationalMemoryScope scope = OperationalMemoryScope.beta(
                ACTOR_A,
                Set.of(WORKSITE_A)
        );

        OperationalMemoryPageResponse first = service.search(
                scope,
                OperationalMemoryFilter.empty(),
                2,
                null
        );
        assertThat(first.items()).extracting(OperationalMemoryEventResponse::commitSequence)
                .containsExactly(105L, 104L);
        assertThat(first.nextCursor()).isEqualTo(
                new OperationalMemoryCursor(104L, eventId(104))
        );
        assertThat(first.highWaterMark()).isEqualTo(105L);
        assertThat(first.coverage().complete()).isTrue();
        assertThat(first.coverage().authorizedEventCount()).isEqualTo(6L);

        OperationalMemoryPageResponse second = service.search(
                scope,
                OperationalMemoryFilter.empty(),
                2,
                first.nextCursor()
        );
        assertThat(second.items()).extracting(OperationalMemoryEventResponse::commitSequence)
                .containsExactly(103L, 102L);
        assertThat(second.items()).extracting(OperationalMemoryEventResponse::eventId)
                .doesNotContainAnyElementsOf(
                        first.items().stream()
                                .map(OperationalMemoryEventResponse::eventId)
                                .toList()
                );
        assertThat(second.scopeHash()).isEqualTo(first.scopeHash());
    }

    @Test
    void appliesStructuralAndUtcFiltersBeforeTheCursor() {
        OperationalMemoryScope scope = OperationalMemoryScope.beta(
                ACTOR_A,
                Set.of(WORKSITE_A)
        );
        OperationalMemoryFilter filter = new OperationalMemoryFilter(
                null,
                "RDO",
                RDO_A,
                WORKSITE_A,
                RDO_A,
                ACTOR_A,
                "RDO_EXECUTADO",
                "OFFLINE",
                "SUCESSO",
                Instant.parse("2026-07-21T10:02:00Z"),
                Instant.parse("2026-07-21T10:04:00Z")
        );

        OperationalMemoryPageResponse page = service.search(scope, filter, 20, null);

        assertThat(page.items())
                .extracting(OperationalMemoryEventResponse::eventId)
                .containsExactly(eventId(103));
    }

    @Test
    void keepsUtcRangeStableWhenTheJvmTimezoneIsNotUtc() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        try {
            OperationalMemoryFilter exactUtcInstant = new OperationalMemoryFilter(
                    null,
                    null,
                    null,
                    WORKSITE_A,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Instant.parse("2026-07-21T10:03:00Z"),
                    Instant.parse("2026-07-21T10:03:00Z")
            );

            assertThat(service.search(
                    OperationalMemoryScope.beta(ACTOR_A, Set.of(WORKSITE_A)),
                    exactUtcInstant,
                    20,
                    null
            ).items()).extracting(OperationalMemoryEventResponse::eventId)
                    .containsExactly(eventId(103));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void rejectsUnboundedOrInvertedDirectQueriesAndForeignScopeCursors() {
        OperationalMemoryScope scope = OperationalMemoryScope.beta(
                ACTOR_A,
                Set.of(WORKSITE_A)
        );

        assertThatThrownBy(() -> search(scope, "q".repeat(201), 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(
                scope,
                new OperationalMemoryFilter(
                        null, null, null, null, null, null, null, null, null,
                        Instant.parse("2026-07-21T12:00:00Z"),
                        Instant.parse("2026-07-21T10:00:00Z")
                ),
                20,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(
                scope,
                OperationalMemoryFilter.empty(),
                20,
                new OperationalMemoryCursor(101L, eventId(101))
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationalMemoryCursor(100L, "x".repeat(161)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsScopeHashOpaqueAndPublicEventProjectionFreeOfRawSensitiveFields() {
        OperationalMemoryPageResponse page = service.search(
                OperationalMemoryScope.beta(ACTOR_A, Set.of(WORKSITE_A)),
                OperationalMemoryFilter.empty(),
                2,
                null
        );

        assertThat(page.scopeHash())
                .matches("[0-9a-f]{64}")
                .doesNotContain(ACTOR_A, WORKSITE_A);
        assertThat(OperationalMemoryEventResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain(
                        "payload",
                        "previousState",
                        "newState",
                        "actorId",
                        "actorName",
                        "email",
                        "cpf",
                        "deviceId"
                );
    }

    @Test
    void upgradesExistingV46HistoryWithoutIndexingArbitraryPayload() {
        try (PostgreSQLContainer<?> upgradeDatabase = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("cortex_operational_memory_upgrade_it")) {
            upgradeDatabase.start();
            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword()
                    )
                    .locations("classpath:db/migration-postgresql")
                    .target("46")
                    .load()
                    .migrate();
            JdbcTemplate upgradeJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    upgradeDatabase.getJdbcUrl(),
                    upgradeDatabase.getUsername(),
                    upgradeDatabase.getPassword()
            ));
            upgradeJdbc.update("""
                    INSERT INTO cortex_evento_operacional (
                        id, commit_seq, tipo_entidade, entidade_id, tipo_evento,
                        fonte, origem, sync_status, resultado, schema_version,
                        payload_json, ocorrido_em
                    ) VALUES (
                        'upgrade-event', 1, 'SERVICE', 'service-upgrade',
                        'SERVICO_EXECUTADO', 'IT', 'ONLINE', 'SYNCED', 'SUCESSO',
                        13, ?::jsonb, timestamp '2026-07-21 10:00:00'
                    )
                    """, "{\"servicoNome\":\"Fresagem permitida\",\"secret\":\"nao-indexar-esta-chave\"}");

            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword()
                    )
                    .locations("classpath:db/migration-postgresql")
                    .load()
                    .migrate();

            assertThat(upgradeJdbc.queryForObject(
                    "SELECT search_text FROM cortex_evento_operacional WHERE id = 'upgrade-event'",
                    String.class
            )).contains("fresagem permitida")
                    .doesNotContain("nao-indexar-esta-chave");
            assertThat(upgradeJdbc.queryForList("""
                    SELECT version
                    FROM flyway_schema_history
                    WHERE type = 'SQL'
                    ORDER BY installed_rank
                    """, String.class)).containsExactly("44", "45", "45.1", "46", "47");
        }
    }

    private static OperationalMemoryPageResponse search(
            OperationalMemoryScope scope,
            String query,
            int limit,
            OperationalMemoryCursor cursor
    ) {
        return service.search(
                scope,
                new OperationalMemoryFilter(
                        query,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                limit,
                cursor
        );
    }

    private static void seed() {
        insertActor(ACTOR_A, "Pessoa Privada A");
        insertActor(ACTOR_B, "Pessoa Privada B");
        insertWorksite(WORKSITE_A, "CTR-A", "Obra Drenagem Norte");
        insertWorksite(WORKSITE_B, "CTR-B", "Obra Drenagem Norte Estrangeira");
        jdbc.update("""
                INSERT INTO vinculo_colaborador_obra (
                    id, obra_id, colaborador_id, status, papel_na_obra
                ) VALUES (?, ?, ?, 'ATIVO', 'OPERACIONAL')
                """, "30000000-0000-0000-0000-00000000000a", WORKSITE_A, ACTOR_A);
        jdbc.update("""
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo, status)
                VALUES (?, ?, 'RDO-NORTE-77', DATE '2026-07-21', 'RASCUNHO')
                """, RDO_A, WORKSITE_A);
        jdbc.update("""
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id, canonical_name
                ) VALUES
                    ('entity-drainage-a', 'RDO', 'rdo', ?, 'Frente Drenagem Principal'),
                    ('entity-person-b', 'COLABORADOR', 'colaborador', ?, 'Pessoa Privada B')
                """, RDO_A, ACTOR_B);

        insertEvent(105, WORKSITE_A, null, "OBRA", WORKSITE_A,
                "OBRA_ATUALIZADA", ACTOR_A, "ONLINE", "{}", "10:05:00");
        insertEvent(104, WORKSITE_A, null, "RDO", RDO_A,
                "FRENTE_DRENAGEM_ATUALIZADA", ACTOR_A, "ONLINE", "{}", "10:04:00");
        insertEvent(103, WORKSITE_A, RDO_A, "RDO", RDO_A,
                "RDO_EXECUTADO", ACTOR_A, "OFFLINE", "{\"numeroRdo\":\"RDO-NORTE-77\"}", "10:03:00");
        insertEvent(102, WORKSITE_A, RDO_A, "SERVICE", "service-a",
                "SERVICO_EXECUTADO", ACTOR_A, "ONLINE",
                "{\"servicoNome\":\"Compactacao asfaltica\",\"secret\":\"segredo-payload-991\",\"cpf\":\"cpf-52998224725\"}",
                "10:02:00");
        insertEvent(101, WORKSITE_B, null, "OBRA", WORKSITE_B,
                "OBRA_ATUALIZADA", ACTOR_B, "ONLINE", "{}", "10:01:00");
        insertEvent(100, WORKSITE_A, RDO_A, "COLABORADOR", ACTOR_B,
                "COLABORADOR_ALOCADO", ACTOR_A, "ONLINE", "{}", "10:00:00");
        insertEvent(99, null, null, "SYSTEM", "self-global",
                "SESSAO_OFFLINE_REGISTRADA", ACTOR_A, "OFFLINE", "{}", "09:59:00");
        insertEvent(98, null, null, "SYSTEM", "foreign-global",
                "SESSAO_OFFLINE_REGISTRADA", ACTOR_B, "OFFLINE", "{}", "09:58:00");
        jdbc.update("UPDATE cortex_evento_commit_sequence SET ultima_commit_seq = 105 WHERE id = 1");
    }

    private static void insertActor(String id, String name) {
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'IT', 'IT', ?, ?, 'BETA')
                """, id, id, name);
    }

    private static void insertWorksite(String id, String contract, String name) {
        jdbc.update("""
                INSERT INTO obra (id, codigo_contrato, nome, status)
                VALUES (?, ?, ?, 'ATIVA')
                """, id, contract, name);
    }

    private static void insertEvent(
            long commitSequence,
            String worksiteId,
            String rdoId,
            String entityType,
            String entityId,
            String eventType,
            String actorId,
            String origin,
            String payload,
            String time
    ) {
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, sincronizado_em,
                    usuario_id, resultado, schema_version, payload_json, ocorrido_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'IT', ?, 'SYNCED',
                          ('2026-07-21 ' || ?)::timestamp, ?, 'SUCESSO', 13,
                          ?::jsonb, ('2026-07-21 ' || ?)::timestamp)
                """,
                eventId(commitSequence),
                commitSequence,
                entityType,
                entityId,
                worksiteId,
                rdoId,
                eventType,
                origin,
                time,
                actorId,
                payload,
                time
        );
    }

    private static String eventId(long sequence) {
        return "event-" + sequence;
    }
}

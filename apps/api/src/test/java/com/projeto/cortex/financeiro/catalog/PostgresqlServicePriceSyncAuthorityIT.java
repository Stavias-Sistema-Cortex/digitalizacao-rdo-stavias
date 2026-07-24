package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.ontology.graph.GraphProjectionCatchUpService;
import com.projeto.cortex.ontology.graph.GraphProjectionService;
import com.projeto.cortex.ontology.graph.OperationalGraphProjector;
import com.projeto.cortex.ontology.graph.PostgresqlCommittedOperationalEventReader;
import com.projeto.cortex.ontology.graph.PostgresqlOntologyGraphRepository;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
class PostgresqlServicePriceSyncAuthorityIT {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final String OCCURRED_AT = "2026-07-22T12:00:00.000Z";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_price_sync_authority_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static DataSourceTransactionManager transactionManager;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void createSupersedeAndCancelKeepExactServerRelationsInAuditAndGraph()
            throws Exception {
        String worksiteId = worksite();
        String actorId = actor();
        String deviceId = device(actorId);
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        ServicePriceCatalogService catalogService = new ServicePriceCatalogService(
                new PostgresqlServicePriceCatalogRepository(jdbc),
                new PostgresqlServiceCatalogOntologyPublisher(memory),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        ServiceCatalogEntry catalog = transactions.execute(status ->
                catalogService.createService(
                        worksiteId,
                        actorId,
                        new CreateServiceCommand(
                                UUID.randomUUID().toString(),
                                "SYNC.AUTHORITY",
                                "Serviço de autoridade",
                                "Fixture de integridade"
                        )
                )
        );
        assertThat(catalog).isNotNull();

        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(actorId);
        when(currentUser.allowedObraIds(actorId))
                .thenReturn(Optional.of(Set.of(worksiteId)));
        FinancialAccessService access = mock(FinancialAccessService.class);
        ServicePriceVersionSyncOperationHandler handler =
                new ServicePriceVersionSyncOperationHandler(
                        catalogService, access, mapper
                );
        SyncService sync = new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                access
        );

        String firstId = UUID.randomUUID().toString();
        ObjectNode createPayload = basePayload(firstId, worksiteId);
        createPayload.put("serviceId", catalog.id());
        createPayload.put("unit", "M2");
        createPayload.put("currency", "BRL");
        createPayload.put("unitPrice", "125.0000");
        createPayload.put("validFrom", "2026-01-01");
        createPayload.put("validTo", "2026-06-30");
        createPayload.put("source", "CONTRATO_MEDIDO");
        SyncPushRequest.MutacaoCliente create = mutation(
                actorId,
                deviceId,
                worksiteId,
                firstId,
                "CRIAR_PRECO_SERVICO",
                "CREATE",
                null,
                createPayload,
                List.of(new SyncPushRequest.RelatedEntity(
                        "SERVICE", catalog.id(), "nome-forjado-no-cliente"
                ))
        );
        SyncPushResponse.ResultadoMutacao created = applied(
                sync.push(new SyncPushRequest(deviceId, List.of(create)))
        );
        assertAudit(
                created.eventoServidorCommitSeq(),
                "SERVICE_PRICE_VERSION_PUBLISHED",
                List.of(
                        "SERVICE:" + catalog.id(),
                        "WORKSITE:" + worksiteId
                ),
                Map.of(
                        "serviceId", catalog.id(),
                        "status", "ACTIVE"
                )
        );

        String replacementId = UUID.randomUUID().toString();
        ObjectNode supersedePayload = basePayload(replacementId, worksiteId);
        supersedePayload.put("previousPriceId", firstId);
        supersedePayload.put("unitPrice", "130.0000");
        supersedePayload.put("validFrom", "2026-07-01");
        supersedePayload.put("source", "ADITIVO");
        SyncPushRequest.MutacaoCliente supersede = mutation(
                actorId,
                deviceId,
                worksiteId,
                replacementId,
                "SUBSTITUIR_PRECO_SERVICO",
                "CREATE",
                null,
                supersedePayload,
                List.of(new SyncPushRequest.RelatedEntity(
                        "SERVICE_PRICE_VERSION",
                        firstId,
                        "predecessor-forjado-no-cliente"
                ))
        );
        SyncPushResponse.ResultadoMutacao superseded = applied(
                sync.push(new SyncPushRequest(deviceId, List.of(supersede)))
        );
        assertAudit(
                superseded.eventoServidorCommitSeq(),
                "SERVICE_PRICE_VERSION_PUBLISHED",
                List.of(
                        "SERVICE:" + catalog.id(),
                        "WORKSITE:" + worksiteId,
                        "SERVICE_PRICE_VERSION:" + firstId
                ),
                Map.of(
                        "serviceId", catalog.id(),
                        "supersedesId", firstId,
                        "status", "ACTIVE"
                )
        );
        assertThat(authoritativeRelationKeys(
                jdbc.queryForObject(
                        """
                        SELECT entidades_relacionadas_json::text
                        FROM cortex_evento_operacional
                        WHERE tipo_evento = 'SERVICE_PRICE_VERSION_SUPERSEDED'
                          AND entidade_id = ?
                          AND correlacao_id = ?
                        """,
                        String.class,
                        firstId,
                        supersede.clientMutationId()
                )
        )).containsExactly(
                "SERVICE:" + catalog.id(),
                "WORKSITE:" + worksiteId
        );

        Long baseVersion = jdbc.queryForObject(
                """
                SELECT versao_entidade
                FROM cortex_estado_entidade
                WHERE tipo_entidade = 'SERVICE_PRICE_VERSION'
                  AND entidade_id = ?
                """,
                Long.class,
                replacementId
        );
        assertThat(baseVersion).isNotNull();
        ObjectNode cancelPayload = basePayload(replacementId, worksiteId);
        cancelPayload.put("effectiveAt", "2026-08-01");
        cancelPayload.put("reason", "Encerrado por aditivo");
        SyncPushRequest.MutacaoCliente cancel = mutation(
                actorId,
                deviceId,
                worksiteId,
                replacementId,
                "CANCELAR_PRECO_SERVICO",
                "TRANSITION",
                baseVersion,
                cancelPayload,
                List.of()
        );
        SyncPushResponse.ResultadoMutacao cancelled = applied(
                sync.push(new SyncPushRequest(deviceId, List.of(cancel)))
        );
        assertAudit(
                cancelled.eventoServidorCommitSeq(),
                "SERVICE_PRICE_VERSION_CANCELLED",
                List.of(
                        "SERVICE:" + catalog.id(),
                        "WORKSITE:" + worksiteId
                ),
                Map.of(
                        "serviceId", catalog.id(),
                        "status", "CANCELLED"
                )
        );

        projectAvailableGraph();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM ontology_relations relation
                JOIN ontology_entities source
                  ON source.id = relation.source_entity_id
                JOIN ontology_entities target
                  ON target.id = relation.target_entity_id
                WHERE relation.relation_type = 'PRICED_BY'
                  AND source.external_ref_id = ?
                  AND target.external_ref_id IN (?, ?)
                """,
                Integer.class,
                catalog.id(),
                firstId,
                replacementId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM ontology_relations relation
                JOIN ontology_entities source
                  ON source.id = relation.source_entity_id
                JOIN ontology_entities target
                  ON target.id = relation.target_entity_id
                WHERE relation.relation_type = 'SUPERSEDES'
                  AND source.external_ref_id = ?
                  AND target.external_ref_id = ?
                """,
                Integer.class,
                replacementId,
                firstId
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT status
                FROM ontology_entities
                WHERE entity_type = 'SERVICE_PRICE_VERSION'
                  AND external_ref_id = ?
                """,
                String.class,
                replacementId
        )).isEqualTo("CANCELLED");
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String actorId,
            String deviceId,
            String worksiteId,
            String entityId,
            String transportOperation,
            String canonicalOperation,
            Long baseVersion,
            ObjectNode payload,
            List<SyncPushRequest.RelatedEntity> related
    ) throws Exception {
        String clientMutationId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        List<String> changedFields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(changedFields::add);
        changedFields.sort(String::compareTo);
        return new SyncPushRequest.MutacaoCliente(
                clientMutationId,
                "SERVICE_PRICE_VERSION",
                entityId,
                transportOperation,
                baseVersion,
                payload,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                correlationId,
                13,
                deviceId,
                actorId,
                worksiteId,
                "SERVICE_PRICE_VERSION",
                entityId,
                canonicalOperation,
                baseVersion,
                changedFields,
                OCCURRED_AT,
                new SyncPushRequest.MutationTrace(
                        actorId,
                        deviceId,
                        List.of(worksiteId),
                        correlationId,
                        null,
                        UUID.randomUUID().toString(),
                        hash(payload)
                ),
                new SyncPushRequest.FieldPatch(
                        payload.deepCopy(),
                        mapper.createObjectNode()
                ),
                related,
                List.of()
        );
    }

    private void assertAudit(
            Long commitSeq,
            String eventType,
            List<String> expectedRelations,
            Map<String, String> expectedState
    ) throws Exception {
        assertThat(commitSeq).isNotNull();
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT tipo_evento,
                       entidades_relacionadas_json::text AS relations,
                       estado_novo_json::text AS state
                FROM cortex_evento_operacional
                WHERE commit_seq = ?
                """,
                commitSeq
        );
        assertThat(row).containsEntry("tipo_evento", eventType);
        assertThat(authoritativeRelationKeys((String) row.get("relations")))
                .containsExactlyElementsOf(expectedRelations);
        assertThat((String) row.get("relations"))
                .doesNotContain("forjado");
        JsonNode state = mapper.readTree((String) row.get("state"));
        expectedState.forEach((field, expected) ->
                assertThat(state.path(field).asText()).isEqualTo(expected)
        );
    }

    private List<String> authoritativeRelationKeys(String json) throws Exception {
        List<String> keys = new ArrayList<>();
        mapper.readTree(json).forEach(item ->
                keys.add(
                        item.path("tipo").asText()
                                + ":"
                                + item.path("id").asText()
                )
        );
        return keys;
    }

    private static SyncPushResponse.ResultadoMutacao applied(
            SyncPushResponse response
    ) {
        assertThat(response.resultados()).singleElement().satisfies(result ->
                assertThat(result.status()).isEqualTo("APLICADA")
        );
        return response.resultados().getFirst();
    }

    private ObjectNode basePayload(String id, String worksiteId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", id);
        payload.put("obraId", worksiteId);
        return payload;
    }

    private String hash(JsonNode payload) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        canonicalJson(payload).getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    private String canonicalJson(JsonNode value) throws Exception {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : value) {
                items.add(canonicalJson(item));
            }
            return "[" + String.join(",", items) + "]";
        }
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            List<String> entries = new ArrayList<>();
            for (String name : names) {
                entries.add(
                        mapper.writeValueAsString(name)
                                + ":"
                                + canonicalJson(value.get(name))
                );
            }
            return "{" + String.join(",", entries) + "}";
        }
        if (value.isNumber()) {
            BigDecimal normalized = value.decimalValue().stripTrailingZeros();
            return normalized.signum() == 0
                    ? "0"
                    : normalized.toPlainString();
        }
        return mapper.writeValueAsString(value);
    }

    private static void projectAvailableGraph() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PostgresqlOntologyGraphRepository repository =
                new PostgresqlOntologyGraphRepository(
                        jdbc, mapper, transactionManager
                );
        new GraphProjectionCatchUpService(
                new PostgresqlCommittedOperationalEventReader(jdbc, mapper),
                new GraphProjectionService(
                        new OperationalGraphProjector(), repository
                ),
                repository
        ).projectAvailable(10_000);
    }

    private static String worksite() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                id,
                "SYNC-AUTH-" + id.substring(0, 8),
                "Obra sync authority"
        );
        return id;
    }

    private static String actor() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'fixture', 'colaborador', ?,
                          'Operador sync authority', 'BETA', TRUE)
                """,
                id,
                id
        );
        return id;
    }

    private static String device(String actorId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO sync_dispositivo (id, usuario_id, ativo)
                VALUES (?, ?, TRUE)
                """,
                id,
                actorId
        );
        jdbc.update(
                "INSERT INTO sync_estado_dispositivo (dispositivo_id) VALUES (?)",
                id
        );
        return id;
    }
}

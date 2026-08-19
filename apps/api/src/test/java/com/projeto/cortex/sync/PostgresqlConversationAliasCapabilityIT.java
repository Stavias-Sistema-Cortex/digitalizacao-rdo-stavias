package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlConversationAliasCapabilityIT {

    private static final String CAPABILITY_HEADER =
            "X-Cortex-Sync-Capabilities";
    private static final String ALIAS_REMAP_CAPABILITY =
            "conversation-alias-remap-v1";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_conversation_alias_capability_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper mapper;

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
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void clienteSemCapacidadeNaoRecebeAliasNemConfirmaAMutacao() throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc().perform(post("/api/sync/push")
                        .contentType("application/json")
                        .content(requestJson(fixture.request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ERRO"));

        assertThat(receiptStatus(fixture.mutationId())).isEqualTo("ERRO");
        assertThat(eventCount(fixture.canonicalId())).isZero();
    }

    @Test
    void clienteComCapacidadeRecebeAliasEConfirmaAMutacao() throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc().perform(post("/api/sync/push")
                        .header(CAPABILITY_HEADER, ALIAS_REMAP_CAPABILITY)
                        .contentType("application/json")
                        .content(requestJson(fixture.request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("APLICADA"))
                .andExpect(jsonPath("$.resultados[0].entidadeId")
                        .value(fixture.canonicalId()))
                .andExpect(jsonPath("$.resultados[0].resultado.id")
                        .value(fixture.canonicalId()));

        assertThat(receiptStatus(fixture.mutationId())).isEqualTo("APLICADA");
        assertThat(eventCount(fixture.canonicalId())).isEqualTo(1);
    }

    @Test
    void clienteAtualizadoReprocessaReciboDeCapacidadeSemPerderAMutacao()
            throws Exception {
        Fixture fixture = fixture();
        fixture.mockMvc().perform(post("/api/sync/push")
                        .contentType("application/json")
                        .content(requestJson(fixture.request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ERRO"));

        fixture.mockMvc().perform(post("/api/sync/push")
                        .header(CAPABILITY_HEADER, ALIAS_REMAP_CAPABILITY)
                        .contentType("application/json")
                        .content(requestJson(fixture.request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("APLICADA"))
                .andExpect(jsonPath("$.resultados[0].entidadeId")
                        .value(fixture.canonicalId()));

        assertThat(receiptStatus(fixture.mutationId())).isEqualTo("APLICADA");
        assertThat(eventCount(fixture.canonicalId())).isEqualTo(1);
        assertThat(fixture.handlerCalls()).hasValue(2);
    }

    @Test
    void replayAplicadoNaoEntregaAliasAoClienteAntigoNemMudaORecibo()
            throws Exception {
        Fixture fixture = fixture();
        fixture.mockMvc().perform(post("/api/sync/push")
                        .header(CAPABILITY_HEADER, ALIAS_REMAP_CAPABILITY)
                        .contentType("application/json")
                        .content(requestJson(fixture.request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("APLICADA"));

        fixture.mockMvc().perform(post("/api/sync/push")
                        .contentType("application/json")
                        .content(requestJson(fixture.request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ERRO"));

        assertThat(receiptStatus(fixture.mutationId())).isEqualTo("APLICADA");
        assertThat(eventCount(fixture.canonicalId())).isEqualTo(1);
        assertThat(fixture.handlerCalls()).hasValue(1);
    }

    @Test
    void envelopeCanonicoNuncaAceitaAliasMesmoComCapacidade() throws Exception {
        Fixture fixture = fixture();
        SyncPushRequest canonicalRequest = canonicalRequest(fixture);

        fixture.mockMvc().perform(post("/api/sync/push")
                        .header(CAPABILITY_HEADER, ALIAS_REMAP_CAPABILITY)
                        .contentType("application/json")
                        .content(requestJson(canonicalRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ERRO"));

        assertThat(receiptStatus(fixture.mutationId())).isEqualTo("ERRO");
        assertThat(eventCount(fixture.canonicalId())).isZero();
        assertThat(fixture.handlerCalls()).hasValue(1);
    }

    private Fixture fixture() {
        String ownerId = collaborator();
        String deviceId = device(ownerId);
        String mutationId = UUID.randomUUID().toString();
        String provisionalId = UUID.randomUUID().toString();
        String canonicalId = UUID.randomUUID().toString();
        AtomicInteger handlerCalls = new AtomicInteger();
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(ownerId);
        when(currentUser.allowedObraIds(ownerId)).thenReturn(Optional.empty());

        CortexOperationalMemoryService memory =
                new CortexOperationalMemoryService(
                        jdbc,
                        mapper,
                        mock(ApplicationEventPublisher.class)
                );
        SyncOperationHandler handler = aliasingConversationHandler(
                canonicalId,
                ownerId,
                handlerCalls,
                memory
        );
        SyncService service = new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SyncController(service))
                .build();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", provisionalId);
        payload.put("tipo", "DIRETA");
        payload.putNull("titulo");
        payload.putNull("obraId");
        payload.putNull("equipeId");
        payload.putArray("participanteIds").add(UUID.randomUUID().toString());
        SyncPushRequest.MutacaoCliente mutation =
                new SyncPushRequest.MutacaoCliente(
                        mutationId,
                        "CONVERSA",
                        provisionalId,
                        "CRIAR_CONVERSA",
                        null,
                        payload,
                        LocalDateTime.of(2026, 8, 19, 14, 30),
                        mutationId
                );

        return new Fixture(
                mockMvc,
                new SyncPushRequest(deviceId, List.of(mutation)),
                mutationId,
                canonicalId,
                handlerCalls,
                ownerId
        );
    }

    private SyncPushRequest canonicalRequest(Fixture fixture) throws Exception {
        SyncPushRequest.MutacaoCliente legacy =
                fixture.request().mutacoes().get(0);
        ObjectNode payload = (ObjectNode) legacy.payload().deepCopy();
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) "
                        + "VALUES (?, ?, 'Obra alias canônico')",
                obraId,
                "ALIAS-" + obraId
        );
        payload.put("obraId", obraId);
        String correlationId = UUID.randomUUID().toString();
        List<String> changedFields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(changedFields::add);
        changedFields.sort(String::compareTo);
        SyncPushRequest.MutacaoCliente mutation =
                new SyncPushRequest.MutacaoCliente(
                        legacy.clientMutationId(),
                        legacy.entidadeTipo(),
                        legacy.entidadeId(),
                        legacy.operacao(),
                        null,
                        payload,
                        legacy.criadaNoClienteEm(),
                        correlationId,
                        13,
                        fixture.request().dispositivoId(),
                        fixture.ownerId(),
                        obraId,
                        "CONVERSA",
                        legacy.entidadeId(),
                        "CREATE",
                        null,
                        changedFields,
                        "2026-08-19T17:30:00.000Z",
                        new SyncPushRequest.MutationTrace(
                                fixture.ownerId(),
                                fixture.request().dispositivoId(),
                                List.of("ALFA:GLOBAL"),
                                correlationId,
                                null,
                                UUID.randomUUID().toString(),
                                hash(payload)
                        ),
                        new SyncPushRequest.FieldPatch(
                                payload.deepCopy(),
                                mapper.createObjectNode()
                        ),
                        List.of(),
                        List.of()
                );
        return new SyncPushRequest(
                fixture.request().dispositivoId(),
                List.of(mutation)
        );
    }

    private SyncOperationHandler aliasingConversationHandler(
            String canonicalId,
            String ownerId,
            AtomicInteger handlerCalls,
            CortexOperationalMemoryService memory
    ) {
        return new SyncOperationHandler() {
            @Override
            public String entityType() {
                return "CONVERSA";
            }

            @Override
            public Set<String> operations() {
                return Set.of("CRIAR_CONVERSA");
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
                handlerCalls.incrementAndGet();
                memory.registrarObjeto(
                        "CONVERSA",
                        canonicalId,
                        null,
                        "Conversa direta canônica",
                        "ATIVO",
                        "ALIAS_CAPABILITY_IT"
                );
                LocalDateTime now = LocalDateTime.of(2026, 8, 19, 14, 31);
                memory.registrarEventoAuditado(
                        null,
                        "CONVERSA",
                        canonicalId,
                        "CONVERSA_CRIADA",
                        "ALIAS_CAPABILITY_IT",
                        null,
                        null,
                        ownerId,
                        List.of(),
                        "OFFLINE",
                        "SYNCED",
                        now,
                        now,
                        1,
                        Map.of("id", canonicalId),
                        ownerId,
                        context.deviceId(),
                        mutation.correlacaoId(),
                        null,
                        Map.of(),
                        Map.of("id", canonicalId),
                        "SUCESSO",
                        null
                );
                ObjectNode result = mapper.createObjectNode();
                result.put("id", canonicalId);
                return new AppliedSyncMutation(
                        "CONVERSA",
                        canonicalId,
                        result
                );
            }
        };
    }

    private String collaborator() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'alias-capability-it', 'colaborador', ?,
                          'Operador de alias', 'BETA', TRUE)
                """,
                id,
                id
        );
        return id;
    }

    private String requestJson(SyncPushRequest request) throws Exception {
        ObjectNode body = mapper.valueToTree(request);
        ((ObjectNode) body.path("mutacoes").get(0)).put(
                "criadaNoClienteEm",
                "2026-08-19T17:30:00.000Z"
        );
        return mapper.writeValueAsString(body);
    }

    private String hash(ObjectNode payload) throws Exception {
        String canonical = canonicalJson(payload);
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String canonicalJson(com.fasterxml.jackson.databind.JsonNode value)
            throws Exception {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : value) {
                values.add(canonicalJson(item));
            }
            return "[" + String.join(",", values) + "]";
        }
        if (value.isObject()) {
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            List<String> entries = new ArrayList<>();
            for (String field : fields) {
                entries.add(mapper.writeValueAsString(field)
                        + ":" + canonicalJson(value.get(field)));
            }
            return "{" + String.join(",", entries) + "}";
        }
        return mapper.writeValueAsString(value);
    }

    private String device(String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, usuario_id, ativo) "
                        + "VALUES (?, ?, TRUE)",
                id,
                ownerId
        );
        jdbc.update(
                "INSERT INTO sync_estado_dispositivo (dispositivo_id) VALUES (?)",
                id
        );
        return id;
    }

    private String receiptStatus(String mutationId) {
        return jdbc.queryForObject(
                "SELECT status FROM sync_mutacao_cliente WHERE client_mutation_id = ?",
                String.class,
                mutationId
        );
    }

    private int eventCount(String entityId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_evento_operacional WHERE entidade_id = ?",
                Integer.class,
                entityId
        );
        return count == null ? 0 : count;
    }

    private record Fixture(
            MockMvc mockMvc,
            SyncPushRequest request,
            String mutationId,
            String canonicalId,
            AtomicInteger handlerCalls,
            String ownerId
    ) {
    }
}

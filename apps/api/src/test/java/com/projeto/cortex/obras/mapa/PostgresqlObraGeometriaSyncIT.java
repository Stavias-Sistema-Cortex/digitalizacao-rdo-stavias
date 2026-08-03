package com.projeto.cortex.obras.mapa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A criação de geometria atravessando o sync canônico inteiro, contra o
 * PostgreSQL real.
 *
 * <p>Este teste existe porque a fila de campo já entalou aqui duas vezes, cada
 * vez numa camada diferente. Primeiro a coluna jsonb recusou o bind VARCHAR e
 * nenhum INSERT acontecia. Corrigido o tipo, o INSERT passou a funcionar — e o
 * defeito de baixo revelou o de cima: o serviço cunhava um id novo no servidor,
 * o contrato canônico conferia a identidade devolvida contra a enviada, e cada
 * criação vinda do dispositivo morria em {@code IllegalStateException} com
 * retentativa infinita, com centenas de tentativas acumuladas em produção. As
 * suítes de unidade não pegaram nenhuma das duas porque nenhuma atravessava
 * {@code SyncService → handler → ObraMapaService → JPA → PostgreSQL} de ponta a
 * ponta — que é exatamente o que este teste faz, com o repositório JPA real por
 * baixo.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlObraGeometriaSyncIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("obra_geometria_sync_it");

    private static SessionFactory sessionFactory;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObraGeometriaRepository featureRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void migrateAndBootJpa() {
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

        Configuration configuration = new Configuration()
                .addAnnotatedClass(ObraGeometria.class);
        // O schema vem do Flyway, como na produção; o Hibernate só grava nele.
        configuration.setProperty("hibernate.hbm2ddl.auto", "none");
        configuration.getProperties()
                .put("hibernate.connection.datasource", dataSource);
        sessionFactory = configuration.buildSessionFactory();

        // O mesmo arranjo transacional da produção: o gerente JPA comanda a
        // transação e expõe a conexão para o JdbcTemplate, de modo que o ledger
        // do sync e a linha da geometria se confirmam — ou desfazem — juntos.
        JpaTransactionManager transactionManager =
                new JpaTransactionManager(sessionFactory);
        transactionManager.setJpaDialect(new HibernateJpaDialect());
        transactionManager.setDataSource(dataSource);
        transactions = new TransactionTemplate(transactionManager);

        featureRepository = new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(sessionFactory)
        ).getRepository(ObraGeometriaRepository.class);
    }

    @AfterAll
    static void closeJpa() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    void fieldCaptureCreatedOfflineDrainsPreservingTheDeviceMintedIdentity()
            throws Exception {
        Fixture fixture = fixture();
        String entityId = UUID.randomUUID().toString();
        SyncPushRequest.MutacaoCliente mutation = fieldCaptureMutation(
                fixture,
                UUID.randomUUID().toString(),
                entityId
        );

        SyncPushResponse response = fixture.service().push(
                new SyncPushRequest(fixture.deviceId(), List.of(mutation))
        );

        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.erro()).as("erro da mutação").isNull();
            assertThat(result.status()).isEqualTo("APLICADA");
            assertThat(result.resultado().path("id").asText()).isEqualTo(entityId);
            // O veredito devolve a versão canônica (1 após a criação): é o
            // número que o dispositivo guarda e reapresenta na primeira edição.
            assertThat(result.resultado().path("versao").asLong()).isEqualTo(1L);
            assertThat(result.resultado().path("versaoEntidade").asLong())
                    .isEqualTo(1L);
        });
        assertThat(jdbc.queryForMap(
                """
                SELECT obra_id, fonte, categoria, versao_linha,
                       geometria_json ->> 'type' AS geometry_type
                FROM obra_geometria
                WHERE id = ?
                """,
                entityId
        )).containsEntry("obra_id", fixture.obraId())
                .containsEntry("fonte", "CAPTURA_CAMPO")
                .containsEntry("categoria", "PONTO_OPERACIONAL")
                .containsEntry("geometry_type", "Point");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sync_mutacao_cliente "
                        + "WHERE proprietario_id = ? AND client_mutation_id = ?",
                String.class,
                fixture.ownerId(),
                mutation.clientMutationId()
        )).isEqualTo("APLICADA");
    }

    @Test
    void replayOfTheSameMutationReturnsTheArchivedVerdictWithoutRewriting()
            throws Exception {
        Fixture fixture = fixture();
        String entityId = UUID.randomUUID().toString();
        SyncPushRequest.MutacaoCliente mutation = fieldCaptureMutation(
                fixture,
                UUID.randomUUID().toString(),
                entityId
        );

        SyncPushResponse first = fixture.service().push(
                new SyncPushRequest(fixture.deviceId(), List.of(mutation))
        );
        SyncPushResponse replay = fixture.service().push(
                new SyncPushRequest(fixture.deviceId(), List.of(mutation))
        );

        assertThat(first.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(replay.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(replay.resultados().getFirst().eventoServidorCommitSeq())
                .isEqualTo(first.resultados().getFirst().eventoServidorCommitSeq());
        assertThat(countRows(entityId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM obra_geometria WHERE id = ?",
                Long.class,
                entityId
        )).isZero();
    }

    /**
     * O reenvio manual da fila em revisão cunha um clientMutationId novo para a
     * mesma entidade — foi assim que os dispositivos entalados reenviaram. A
     * segunda travessia precisa convergir no registro existente, não morrer em
     * chave duplicada.
     */
    @Test
    void resendUnderANewMutationIdentityConvergesOnTheExistingRow()
            throws Exception {
        Fixture fixture = fixture();
        String entityId = UUID.randomUUID().toString();
        SyncPushResponse first = fixture.service().push(new SyncPushRequest(
                fixture.deviceId(),
                List.of(fieldCaptureMutation(
                        fixture, UUID.randomUUID().toString(), entityId
                ))
        ));
        SyncPushResponse resent = fixture.service().push(new SyncPushRequest(
                fixture.deviceId(),
                List.of(fieldCaptureMutation(
                        fixture, UUID.randomUUID().toString(), entityId
                ))
        ));

        assertThat(first.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(resent.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(resent.resultados().getFirst().resultado().path("id").asText())
                .isEqualTo(entityId);
        assertThat(countRows(entityId)).isEqualTo(1);
    }

    /**
     * O trecho desenhado no mapa, que é o grosso da fila entalada em campo.
     * Mesma travessia, outra porta de criação: categoria TRECHO, linha em vez
     * de ponto e o método restrito ao Alfa.
     */
    @Test
    void drawnStretchAlsoDrainsPreservingTheDeviceMintedIdentity()
            throws Exception {
        Fixture fixture = fixture();
        String entityId = UUID.randomUUID().toString();

        SyncPushResponse response = fixture.service().push(new SyncPushRequest(
                fixture.deviceId(),
                List.of(drawnStretchMutation(
                        fixture, UUID.randomUUID().toString(), entityId
                ))
        ));

        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.erro()).as("erro da mutação").isNull();
            assertThat(result.status()).isEqualTo("APLICADA");
            assertThat(result.resultado().path("id").asText()).isEqualTo(entityId);
        });
        assertThat(jdbc.queryForMap(
                """
                SELECT categoria, fonte, tipo_geometria,
                       propriedades_json ->> 'servico' AS servico
                FROM obra_geometria
                WHERE id = ?
                """,
                entityId
        )).containsEntry("categoria", "TRECHO")
                .containsEntry("fonte", "GESTAO_MAPA")
                .containsEntry("tipo_geometria", "LINESTRING")
                .containsEntry("servico", "Pavimentação CBUQ");
    }

    /**
     * A atualização atravessa o mesmo funil e dependia do mesmo nome de
     * entidade na memória operacional: com a grafia divergente, o commit do
     * evento nunca era encontrado e a mutação retentava para sempre.
     */
    @Test
    void updateThroughTheCanonicalFunnelAlsoDrains() throws Exception {
        Fixture fixture = fixture();
        String entityId = UUID.randomUUID().toString();
        SyncPushResponse created = fixture.service().push(new SyncPushRequest(
                fixture.deviceId(),
                List.of(fieldCaptureMutation(
                        fixture, UUID.randomUUID().toString(), entityId
                ))
        ));
        assertThat(created.resultados().getFirst().status()).isEqualTo("APLICADA");

        // A régua de versão é uma só: a versão canônica da entidade, que o
        // evento de criação deixa em 1 — o mesmo número que o dispositivo
        // recebe no veredito e devolve como baseVersion na primeira edição.
        SyncPushResponse updated = fixture.service().push(new SyncPushRequest(
                fixture.deviceId(),
                List.of(updateMutation(
                        fixture, UUID.randomUUID().toString(), entityId, 1L
                ))
        ));

        assertThat(updated.resultados()).singleElement().satisfies(result -> {
            assertThat(result.erro()).as("erro da mutação").isNull();
            assertThat(result.status()).isEqualTo("APLICADA");
        });
        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM obra_geometria WHERE id = ?",
                Long.class,
                entityId
        )).isEqualTo(1L);
    }

    private SyncPushRequest.MutacaoCliente updateMutation(
            Fixture fixture,
            String clientMutationId,
            String entityId,
            long baseVersion
    ) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", entityId);
        payload.put("obraId", fixture.obraId());
        payload.put("categoria", "PONTO_OPERACIONAL");
        payload.put("objetoTipo", "RDO");
        payload.put("objetoId", UUID.randomUUID().toString());
        ObjectNode geometry = payload.putObject("geometry");
        geometry.put("type", "Point");
        geometry.putArray("coordinates").add(-54.66).add(-20.45);
        payload.putObject("properties").put("precisaoM", 3.1);
        payload.put("fonte", "CAPTURA_CAMPO");
        payload.put("motivo", "Correção topográfica");

        List<String> changedFields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(changedFields::add);
        changedFields.sort(String::compareTo);
        String correlationId = UUID.randomUUID().toString();
        return new SyncPushRequest.MutacaoCliente(
                clientMutationId,
                "GEOMETRIA_OBRA",
                entityId,
                "ATUALIZAR_GEOMETRIA_OBRA",
                baseVersion,
                payload,
                LocalDateTime.parse("2026-08-01T11:00:00"),
                correlationId,
                13,
                fixture.deviceId(),
                fixture.ownerId(),
                fixture.obraId(),
                "GEOMETRIA_OBRA",
                entityId,
                "UPDATE",
                baseVersion,
                changedFields,
                "2026-08-01T14:00:00.000Z",
                new SyncPushRequest.MutationTrace(
                        fixture.ownerId(),
                        fixture.deviceId(),
                        List.of(fixture.obraId()),
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
    }

    private int countRows(String entityId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM obra_geometria WHERE id = ?",
                Integer.class,
                entityId
        );
        return count == null ? 0 : count;
    }

    private Fixture fixture() {
        String ownerId = collaborator();
        String obraId = worksite();
        String deviceId = device(ownerId);

        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(ownerId);
        when(currentUser.allowedObraIds(ownerId))
                .thenReturn(Optional.of(Set.of(obraId)));

        ObraRepository obraRepository = mock(ObraRepository.class);
        when(obraRepository.findWritableIdForShare(obraId))
                .thenReturn(Optional.of(obraId));
        when(obraRepository.findExistingIdForShare(obraId))
                .thenReturn(Optional.of(obraId));

        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        ObraMapaService mapaService = new ObraMapaService(
                obraRepository,
                featureRepository,
                currentUser,
                new ObraGeometriaMemoryPublisher(memory),
                mapper,
                new ObraOperabilityGuard(obraRepository)
        );
        SyncService service = new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(
                        new ObraGeometriaSyncOperationHandler(mapaService, mapper)
                )),
                currentUser,
                mock(FinancialAccessService.class)
        );
        return new Fixture(ownerId, obraId, deviceId, service);
    }

    /** O envelope idêntico ao que a PWA enfileira ao capturar um ponto. */
    private SyncPushRequest.MutacaoCliente fieldCaptureMutation(
            Fixture fixture,
            String clientMutationId,
            String entityId
    ) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", entityId);
        payload.put("obraId", fixture.obraId());
        payload.put("categoria", "PONTO_OPERACIONAL");
        payload.put("objetoTipo", "RDO");
        payload.put("objetoId", UUID.randomUUID().toString());
        ObjectNode geometry = payload.putObject("geometry");
        geometry.put("type", "Point");
        geometry.putArray("coordinates").add(-54.65).add(-20.44);
        payload.putObject("properties").put("precisaoM", 4.5);
        payload.put("fonte", "CAPTURA_CAMPO");
        payload.put("validoDesde", "2026-08-01T10:00:00.000Z");
        payload.putNull("validoAte");
        return createMutation(
                fixture,
                clientMutationId,
                entityId,
                "REGISTRAR_GEOMETRIA_CAMPO",
                payload
        );
    }

    /**
     * O envelope do trecho desenhado sobre o mapa.
     *
     * É a outra porta de criação — a que passa pelo método restrito ao Alfa,
     * com linha em vez de ponto — e é dela que vem a maior parte da fila
     * entalada em campo. Cobrir só a captura de ponto deixaria metade do
     * caminho corrigido sem prova.
     */
    private SyncPushRequest.MutacaoCliente drawnStretchMutation(
            Fixture fixture,
            String clientMutationId,
            String entityId
    ) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", entityId);
        payload.put("obraId", fixture.obraId());
        payload.put("categoria", "TRECHO");
        payload.put("objetoTipo", "TRECHO");
        payload.put("objetoId", UUID.randomUUID().toString());
        ObjectNode geometry = payload.putObject("geometry");
        geometry.put("type", "LineString");
        ArrayNode coordinates = geometry.putArray("coordinates");
        coordinates.addArray().add(-54.65).add(-20.44);
        coordinates.addArray().add(-54.63).add(-20.43);
        payload.putObject("properties").put("servico", "Pavimentação CBUQ");
        payload.put("fonte", "GESTAO_MAPA");
        payload.put("validoDesde", "2026-08-01T10:00:00.000Z");
        payload.putNull("validoAte");
        return createMutation(
                fixture,
                clientMutationId,
                entityId,
                "REGISTRAR_GEOMETRIA_OBRA",
                payload
        );
    }

    private SyncPushRequest.MutacaoCliente createMutation(
            Fixture fixture,
            String clientMutationId,
            String entityId,
            String operation,
            ObjectNode payload
    ) throws Exception {
        List<String> changedFields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(changedFields::add);
        changedFields.sort(String::compareTo);
        String correlationId = UUID.randomUUID().toString();
        return new SyncPushRequest.MutacaoCliente(
                clientMutationId,
                "GEOMETRIA_OBRA",
                entityId,
                operation,
                null,
                payload,
                LocalDateTime.parse("2026-08-01T10:00:00"),
                correlationId,
                13,
                fixture.deviceId(),
                fixture.ownerId(),
                fixture.obraId(),
                "GEOMETRIA_OBRA",
                entityId,
                "CREATE",
                null,
                changedFields,
                "2026-08-01T13:00:00.000Z",
                new SyncPushRequest.MutationTrace(
                        fixture.ownerId(),
                        fixture.deviceId(),
                        List.of(fixture.obraId()),
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
    }

    private String hash(ObjectNode payload) throws Exception {
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
            List<String> values = new ArrayList<>();
            for (JsonNode item : value) {
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

    private String collaborator() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'geometria-sync-it', 'colaborador', ?,
                          'Apontadora de campo', 'BETA', TRUE)
                """,
                id,
                id
        );
        return id;
    }

    private String worksite() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) "
                        + "VALUES (?, ?, 'Obra da captura de campo')",
                id,
                "GEO-" + id.substring(0, 8)
        );
        return id;
    }

    private String device(String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, usuario_id, ativo) VALUES (?, ?, TRUE)",
                id,
                ownerId
        );
        jdbc.update(
                "INSERT INTO sync_estado_dispositivo (dispositivo_id) VALUES (?)",
                id
        );
        return id;
    }

    private record Fixture(
            String ownerId,
            String obraId,
            String deviceId,
            SyncService service
    ) {
    }
}

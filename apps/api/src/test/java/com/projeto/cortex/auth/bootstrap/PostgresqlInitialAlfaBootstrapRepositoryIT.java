package com.projeto.cortex.auth.bootstrap;

import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.integracoes.AcademyBootstrapUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresqlInitialAlfaBootstrapRepositoryIT {

    private static final String SYNTHETIC_CPF = "11144477735";
    private static final String HMAC_SECRET =
            "test-only-bootstrap-hmac-secret-material-0001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndReplaysOneRedactedInitialAlfaWithoutImportingLegacyData() throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            insertIdentityOnlyPredecessor(jdbc);
            AcademyBootstrapLookup academy = academyLookup(user(901));
            PostgresqlInitialAlfaBootstrapService service = service(
                    jdbc, database, academy, temporaryDirectory.resolve("owner-secret")
            );

            assertThat(service.bootstrap()).isEqualTo(
                    PostgresqlInitialAlfaBootstrapService.BootstrapResult.CREATED
            );

            String collaboratorId = jdbc.queryForObject(
                    "SELECT id FROM colaborador WHERE banco_origem = 'dbstavias_acad'",
                    String.class
            );
            assertThat(collaboratorId).isNotNull();
            assertThat(jdbc.queryForMap("""
                    SELECT papel_acesso, ativo, cpf_hash
                    FROM colaborador WHERE id = ?
                    """, collaboratorId))
                    .containsEntry("papel_acesso", "ALFA")
                    .containsEntry("ativo", true)
                    .containsEntry("cpf_hash", null);
            assertThat(jdbc.queryForMap("""
                    SELECT status, email_fonte
                    FROM auth_identity WHERE colaborador_id = ?
                    """, collaboratorId))
                    .containsEntry("status", "PENDENTE")
                    .containsEntry("email_fonte", "ACADEMY");
            assertThat(jdbc.queryForMap("""
                    SELECT ativa, concedida_por, justificativa_concessao
                    FROM auth_capacidade_administrativa
                    WHERE colaborador_id = ?
                    """, collaboratorId))
                    .containsEntry("ativa", true)
                    .containsEntry("concedida_por", collaboratorId)
                    .containsEntry(
                            "justificativa_concessao",
                            PostgresqlInitialAlfaBootstrapRepository.CAPABILITY_RATIONALE
                    );
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_provisioning_receipt",
                    Integer.class
            )).isEqualTo(1);

            var event = jdbc.queryForMap("""
                    SELECT sequencia, commit_seq, payload_json::text AS payload,
                           fonte, tipo_evento, origem, sync_status, resultado,
                           schema_version
                    FROM cortex_evento_operacional
                    WHERE tipo_evento = 'BOOTSTRAP_ALFA_INITIALIZED'
                    """);
            String payload = (String) event.get("payload");
            assertThat(event)
                    .containsEntry("fonte", "ACADEMY")
                    .containsEntry("tipo_evento", "BOOTSTRAP_ALFA_INITIALIZED")
                    .containsEntry("origem", "ONLINE")
                    .containsEntry("sync_status", "SYNCED")
                    .containsEntry("resultado", "SUCESSO")
                    .containsEntry("schema_version", 1);
            assertThat(payload)
                    .contains("INITIAL_ALFA_BOOTSTRAP", "ADMINISTRAR_PAPEIS")
                    .doesNotContain(SYNTHETIC_CPF, "synthetic.owner@example.invalid", "901")
                    .doesNotContain("hmac", "digest", "sourceUserId");
            Long eventSequence = ((Number) event.get("sequencia")).longValue();
            Long commitSequence = ((Number) event.get("commit_seq")).longValue();
            assertThat(eventSequence).isNotEqualTo(commitSequence);
            assertThat(jdbc.queryForObject("""
                    SELECT ultimo_evento_seq
                    FROM cortex_estado_entidade
                    WHERE tipo_entidade = 'COLABORADOR' AND entidade_id = ?
                    """, Long.class, collaboratorId))
                    .isEqualTo(eventSequence)
                    .isNotEqualTo(commitSequence);

            jdbc.update("""
                    UPDATE auth_identity
                    SET status = 'ATIVA', email_verificado_em = CURRENT_TIMESTAMP(6)
                    WHERE colaborador_id = ?
                    """, collaboratorId);
            assertThat(service.bootstrap()).isEqualTo(
                    PostgresqlInitialAlfaBootstrapService.BootstrapResult.ALREADY_APPLIED
            );
            assertCounts(jdbc, 1, 1, 1, 1, 2);

            PostgresqlInitialAlfaBootstrapService mismatchedSource = service(
                    jdbc, database, academyLookup(user(902)), temporaryDirectory.resolve("owner-secret")
            );
            assertThatThrownBy(mismatchedSource::bootstrap)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bootstrap inicial do ALFA não pôde ser concluído.");
            assertCounts(jdbc, 1, 1, 1, 1, 2);
        }
    }

    @Test
    void serializesConcurrentFirstRunsThroughTheProtectedReceiptLock() throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            Path secret = temporaryDirectory.resolve("concurrent-owner-secret");
            PostgresqlInitialAlfaBootstrapService first = service(
                    jdbc, database, academyLookup(user(903)), secret
            );
            PostgresqlInitialAlfaBootstrapService second = service(
                    jdbc, database, academyLookup(user(903)), secret
            );
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Callable<PostgresqlInitialAlfaBootstrapService.BootstrapResult> task = () -> {
                    start.await();
                    return first.bootstrap();
                };
                Future<PostgresqlInitialAlfaBootstrapService.BootstrapResult> one = executor.submit(task);
                Future<PostgresqlInitialAlfaBootstrapService.BootstrapResult> two = executor.submit(() -> {
                    start.await();
                    return second.bootstrap();
                });
                start.countDown();
                List<PostgresqlInitialAlfaBootstrapService.BootstrapResult> results = List.of(
                        one.get(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                        two.get(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                );
                assertThat(results).containsExactlyInAnyOrder(
                        PostgresqlInitialAlfaBootstrapService.BootstrapResult.CREATED,
                        PostgresqlInitialAlfaBootstrapService.BootstrapResult.ALREADY_APPLIED
                );
            }
            assertCounts(jdbc, 1, 1, 1, 1, 1);
        }
    }

    @Test
    void sourceFailureWritesNothingToPostgresql() throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            AcademyBootstrapLookup unavailable = mock(AcademyBootstrapLookup.class);
            when(unavailable.lookupSingleActive(anyString())).thenThrow(new IllegalStateException(
                    "Fonte Academy indisponível para bootstrap."
            ));
            PostgresqlInitialAlfaBootstrapService service = service(
                    jdbc, database, unavailable, temporaryDirectory.resolve("source-failure-secret")
            );

            assertThatThrownBy(service::bootstrap)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bootstrap inicial do ALFA não pôde ser concluído.");
            assertCounts(jdbc, 0, 0, 0, 0, 0);

            jdbc.update("DELETE FROM cortex_evento_commit_sequence WHERE id = 1");
            PostgresqlInitialAlfaBootstrapService databaseFailure = service(
                    jdbc,
                    database,
                    academyLookup(user(904)),
                    temporaryDirectory.resolve("database-failure-secret")
            );
            assertThatThrownBy(databaseFailure::bootstrap)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bootstrap inicial do ALFA não pôde ser concluído.");
            assertCounts(jdbc, 0, 0, 0, 0, 0);
        }
    }

    private PostgresqlInitialAlfaBootstrapService service(
            JdbcTemplate jdbc,
            PostgreSQLContainer<?> database,
            AcademyBootstrapLookup academy,
            Path secretPath
    ) throws Exception {
        writeOwnerOnlySecret(secretPath);
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setAdminCpfFile(secretPath.toString());
        PlatformTransactionManager transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()
        );
        PostgresqlBootstrapMemoryAuditRepository audit =
                new PostgresqlBootstrapMemoryAuditRepository(jdbc);
        PostgresqlInitialAlfaBootstrapRepository repository =
                new PostgresqlInitialAlfaBootstrapRepository(jdbc, audit);
        return new PostgresqlInitialAlfaBootstrapService(
                properties,
                new BootstrapCpfSecretReader(),
                new HmacCpfLookupDigestService("test-kid", null, HMAC_SECRET, null),
                academy,
                repository,
                transactions
        );
    }

    private static AcademyBootstrapLookup academyLookup(AcademyBootstrapUser user) {
        AcademyBootstrapLookup academy = mock(AcademyBootstrapLookup.class);
        when(academy.lookupSingleActive(anyString())).thenReturn(user);
        return academy;
    }

    private static AcademyBootstrapUser user(int sourceId) {
        return new AcademyBootstrapUser(
                sourceId,
                "Synthetic Owner",
                "synthetic.owner@example.invalid",
                true,
                "group-1",
                "Synthetic Group",
                "profile-1",
                "Synthetic Profile",
                null
        );
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

    private static void insertIdentityOnlyPredecessor(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, tipo_evento,
                    fonte, origem, sync_status, resultado, schema_version
                ) VALUES (
                    '00000000-0000-0000-0000-000000000001', 0,
                    'TESTE', '00000000-0000-0000-0000-000000000002',
                    'TESTE_PREDECESSOR', 'SISTEMA', 'ONLINE', 'SYNCED', 'SUCESSO', 1
                )
                """);
    }

    private static void assertCounts(
            JdbcTemplate jdbc,
            int collaborators,
            int identities,
            int capabilities,
            int receipts,
            int events
    ) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM colaborador", Integer.class))
                .isEqualTo(collaborators);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_identity", Integer.class))
                .isEqualTo(identities);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_capacidade_administrativa", Integer.class
        )).isEqualTo(capabilities);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_provisioning_receipt", Integer.class
        )).isEqualTo(receipts);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_evento_operacional", Integer.class
        )).isEqualTo(events);
    }

    private static void writeOwnerOnlySecret(Path path) throws Exception {
        Files.writeString(path, SYNTHETIC_CPF);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
    }
}

package com.projeto.cortex.postgresql;

import com.projeto.cortex.CortexApplication;
import com.projeto.cortex.auth.AuthController;
import com.projeto.cortex.auth.postgresql.PostgresqlAuthPersistenceTestSupport;
import com.projeto.cortex.financeiro.ItemContratualController;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.ontology.graph.GraphProjectionService;
import com.projeto.cortex.ontology.graph.OntologyGraphController;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.pdor.RealPdorInputLoader;
import com.projeto.cortex.rdos.RdoController;
import com.projeto.cortex.sync.SyncController;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = CortexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("postgresql")
class PostgresqlCortexRuntimeIT extends PostgresqlAuthPersistenceTestSupport {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            database();
    private static final Path OTP_KEY_FILE = secretFile('a');
    private static final Path CPF_KEY_FILE = secretFile('b');
    private static final Path SMTP_PASSWORD_FILE = secretFile('c');
    private static final OfflineKeyFiles OFFLINE_KEYS = offlineKeyFiles();

    @DynamicPropertySource
    static void configurePostgresqlRuntime(DynamicPropertyRegistry properties) {
        DATABASE.start();
        migrateAndSeedRuntimeReadiness();
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("cortex.postgresql.runtime-ready", () -> "true");
        properties.add("cortex.auth.otp.hmac-key-file", OTP_KEY_FILE::toString);
        properties.add("cortex.auth.cpf-hmac.current-key-id", () -> "runtime-it");
        properties.add("cortex.auth.cpf-hmac.current-key-file", CPF_KEY_FILE::toString);
        properties.add("cortex.email.provider", () -> "smtp");
        properties.add("cortex.email.from", () -> "runtime.it@fixture.invalid");
        properties.add("cortex.email.smtp.host", () -> "127.0.0.1");
        properties.add("cortex.email.smtp.username", () -> "runtime-it");
        properties.add("cortex.email.smtp.password-file", SMTP_PASSWORD_FILE::toString);
        properties.add("cortex.storage.provider", () -> "s3");
        properties.add("cortex.storage.s3.bucket", () -> "runtime-it");
        properties.add("cortex.storage.s3.region", () -> "us-east-1");
        properties.add("cortex.auth.offline-grant.key-id", () -> "runtime-it");
        properties.add(
                "cortex.auth.offline-grant.private-key-file",
                OFFLINE_KEYS.privateKey()::toString
        );
        properties.add(
                "cortex.auth.offline-grant.public-key-file",
                OFFLINE_KEYS.publicKey()::toString
        );
        properties.add("cortex.cors.allowed-origins", () -> "https://runtime.fixture.invalid");
        properties.add("cortex.auth.webauthn.rp-id", () -> "runtime.fixture.invalid");
        properties.add("cortex.auth.webauthn.rp-name", () -> "Cortex Runtime IT");
        properties.add(
                "cortex.auth.webauthn.allowed-origins",
                () -> "https://runtime.fixture.invalid"
        );
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void startsCompleteRuntimeAndExposesOperationalBeans() throws Exception {
        assertThat(context.getBeansOfType(RdoController.class)).hasSize(1);
        assertThat(context.getBeansOfType(ItemContratualController.class)).hasSize(1);
        assertThat(context.getBeansOfType(OntologyGraphController.class)).hasSize(1);
        assertThat(context.getBeansOfType(CortexOperationalMemoryService.class)).hasSize(1);
        assertThat(context.getBeansOfType(SyncController.class)).hasSize(1);
        assertThat(context.getBeansOfType(AuthController.class)).hasSize(1);
        assertThat(context.getBeansOfType(GraphProjectionService.class)).hasSize(1);

        DataSource dataSource = context.getBean(DataSource.class);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo(DATABASE.getDatabaseName());
        }
    }

    @Test
    void persistsAndQueriesOperationalMemoryWithPostgresqlSql() {
        CortexOperationalMemoryService memory =
                context.getBean(CortexOperationalMemoryService.class);
        String entityId = "00000000-0000-4000-8000-000000000602";

        memory.registrarObjeto(
                "SERVICO", entityId, "SRV-RUNTIME", "Serviço runtime",
                "ATIVO", "RUNTIME_IT", "item_contratual", Map.of("offline", true)
        );
        memory.registrarObjeto(
                "SERVICO", entityId, "SRV-RUNTIME", "Serviço runtime atualizado",
                "ATIVO", "RUNTIME_IT", "item_contratual", Map.of("offline", true)
        );
        memory.registrarEvidencia(
                "SERVICO", entityId, "quantidade", 2, "NUMERO",
                "RUNTIME_IT", null, Map.of("unidade", "m2")
        );
        memory.registrarMapeamentoLegado(
                "SERVICO", entityId, "RUNTIME_IT", "servico", "602",
                null, null, Map.of("source", "offline")
        );
        long commitSeq = memory.registrarEvento(
                "SERVICO", entityId, "SERVICO_RUNTIME_VALIDADO",
                "RUNTIME_IT", null, Map.of("quantidade", 2)
        );

        assertThat(commitSeq).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT nome FROM cortex_objeto WHERE tipo_entidade = ? AND entidade_id = ?",
                String.class, "SERVICO", entityId
        )).isEqualTo("Serviço runtime atualizado");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_evidencia_operacional WHERE entidade_id = ?",
                Integer.class, entityId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payload_json ->> 'quantidade' FROM cortex_evento_operacional "
                        + "WHERE commit_seq = ?",
                String.class, commitSeq
        )).isEqualTo("2");
    }

    @Test
    @Transactional
    void keepsOperationalMemoryTransactionUsableWhenActiveRelationAlreadyExists() {
        CortexOperationalMemoryService memory =
                context.getBean(CortexOperationalMemoryService.class);
        String originId = "00000000-0000-4000-8000-000000000603";
        String destinationId = "00000000-0000-4000-8000-000000000604";

        memory.registrarRelacaoAtiva(
                "CENTRO_CUSTO", originId, "OBRA", destinationId,
                "VINCULADO_A_OBRA", "RUNTIME_IT", null
        );
        memory.registrarRelacaoAtiva(
                "CENTRO_CUSTO", originId, "OBRA", destinationId,
                "VINCULADO_A_OBRA", "RUNTIME_IT", null
        );

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_relacao WHERE origem_id = ? AND ativa = TRUE",
                Integer.class, originId
        )).isEqualTo(1);
    }

    @Test
    void loadsPdorInputsWithNullReferenceDateOnPostgresql() {
        Obra obra = Obra.criar(
                "CW-RUNTIME-PDOR", "CW-RUNTIME-PDOR", null,
                "Obra runtime PDOR", "Runtime IT", null,
                "Sao Paulo", "SP", null, "ATIVA", "RUNTIME_IT", null, null
        );

        assertThat(new RealPdorInputLoader(jdbc).load(obra, null)).isNotNull();
    }

    private static void migrateAndSeedRuntimeReadiness() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        ));
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, email, papel_acesso, ativo
                ) VALUES (
                    '00000000-0000-4000-8000-000000000601',
                    'runtime-it', 'runtime-it', 'alfa',
                    'Runtime IT Alfa', 'runtime.it@fixture.invalid', 'ALFA', TRUE
                )
                """);
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id, email_autenticacao, email_fonte, status,
                    email_verificado_em
                ) VALUES (
                    '00000000-0000-4000-8000-000000000601',
                    'runtime.it@fixture.invalid', 'TEST', 'ATIVA', CURRENT_TIMESTAMP
                )
                """);
    }

    private static Path secretFile(char character) {
        try {
            Path file = Files.createTempFile("cortex-runtime-it-", ".secret");
            Files.writeString(file, String.valueOf(character).repeat(64));
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível preparar secret file do IT.", exception);
        }
    }

    private static OfflineKeyFiles offlineKeyFiles() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2_048);
            var pair = generator.generateKeyPair();
            Path privateKey = pemFile("PRIVATE KEY", pair.getPrivate().getEncoded());
            Path publicKey = pemFile("PUBLIC KEY", pair.getPublic().getEncoded());
            return new OfflineKeyFiles(privateKey, publicKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível preparar chaves do IT.", exception);
        }
    }

    private static Path pemFile(String type, byte[] encoded) throws IOException {
        Path file = Files.createTempFile("cortex-runtime-it-", ".pem");
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        Files.writeString(file, "-----BEGIN " + type + "-----\n"
                + body + "\n-----END " + type + "-----\n");
        file.toFile().deleteOnExit();
        return file;
    }

    private record OfflineKeyFiles(Path privateKey, Path publicKey) {
    }
}

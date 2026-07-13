package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.identity.AuthIdentityProvisioningRunner;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.auth.identity.ProvisioningReceiptRepository;
import com.projeto.cortex.colaboradores.CpfHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CORTEX_MYSQL_ROOT_PASSWORD", matches = ".+")
class AuthProvisioningReceiptMysqlIntegrationTest {

    private static final String FIRST_CPF = "11144477735";
    private static final String SECOND_CPF = "90000007935";
    private static final String HMAC_SECRET =
            "test-only-provisioning-hmac-material-0001";

    @TempDir
    Path tempDir;

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void twoConcurrentTransactionsClaimReceiptExactlyOnce() throws Exception {
        database = migratedDatabase("prov_receipt_race");
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ProvisioningReceiptRepository receipts =
                new ProvisioningReceiptRepository(jdbc);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> claim(
                    receipts,
                    transactions,
                    start
            ));
            Future<Boolean> second = executor.submit(() -> claim(
                    receipts,
                    transactions,
                    start
            ));

            assertThat(Set.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(false, true);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_provisioning_receipt",
                    Integer.class
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void laterIdentityFailureRollsBackReceiptAndEveryIdentityWrite()
            throws Exception {
        database = migratedDatabase("prov_receipt_rb");
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertCollaborator(jdbc, "first", FIRST_CPF);
        insertCollaborator(jdbc, "second", SECOND_CPF);

        HmacCpfLookupDigestService digests =
                new HmacCpfLookupDigestService(
                        "test-current",
                        null,
                        HMAC_SECRET,
                        null
                );
        AuthIdentityRepository failingSecondIdentity =
                new AuthIdentityRepository(jdbc, digests) {
                    private int calls;

                    @Override
                    public void upsertProvisionedIdentity(
                            String cpfRaw,
                            String email,
                            String source
                    ) {
                        calls++;
                        if (calls == 2) {
                            throw new IllegalStateException(
                                    "Falha sintética sanitizada."
                            );
                        }
                        super.upsertProvisionedIdentity(cpfRaw, email, source);
                    }
                };
        Path manifest = provisioningManifest();
        AuthIdentityProvisioningRunner runner =
                new AuthIdentityProvisioningRunner(
                        manifest.toString(),
                        "none",
                        new ObjectMapper(),
                        failingSecondIdentity,
                        new ProvisioningReceiptRepository(jdbc)
                );
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                ignored -> runner.run()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha sintética sanitizada.");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_provisioning_receipt",
                Integer.class
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_identity",
                Integer.class
        )).isZero();
    }

    @Test
    void concurrentBlockedTransitionCannotBeDowngradedByProvisioning()
            throws Exception {
        database = migratedDatabase("prov_block_race");
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String collaboratorId = insertCollaborator(
                jdbc,
                "blocked-race",
                FIRST_CPF
        );
        HmacCpfLookupDigestService digests =
                new HmacCpfLookupDigestService(
                        "test-current",
                        null,
                        HMAC_SECRET,
                        null
                );
        CpfLookupDigest digest = digests.current(FIRST_CPF);
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    cpf_lookup_hmac,
                    cpf_lookup_key_id,
                    email_autenticacao,
                    email_fonte,
                    status
                ) VALUES (?, ?, ?, 'protected@example.invalid',
                          'MANUAL_VERIFICADO', 'ATIVA')
                """,
                collaboratorId,
                digest.value(),
                digest.keyId()
        );

        AuthIdentityRepository identities =
                new AuthIdentityRepository(jdbc, digests);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        CountDownLatch blockedRowLocked = new CountDownLatch(1);
        CountDownLatch releaseBlockedTransaction = new CountDownLatch(1);
        CountDownLatch provisioningTransactionStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Void> blocker = executor.submit(() -> {
            new TransactionTemplate(transactions).executeWithoutResult(
                    ignored -> {
                        jdbc.update("""
                                UPDATE auth_identity
                                SET status = 'BLOQUEADA'
                                WHERE colaborador_id = ?
                                """,
                                collaboratorId
                        );
                        blockedRowLocked.countDown();
                        await(releaseBlockedTransaction);
                    }
            );
            return null;
        });

        try {
            await(blockedRowLocked);
            Future<Throwable> provisioning = executor.submit(() -> {
                try {
                    new TransactionTemplate(transactions)
                            .executeWithoutResult(ignored -> {
                                provisioningTransactionStarted.countDown();
                                identities.upsertProvisionedIdentity(
                                        FIRST_CPF,
                                        "replacement@example.invalid",
                                        AuthIdentityRepository
                                                .MANUAL_PENDING_SOURCE
                                );
                            });
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });
            await(provisioningTransactionStarted);
            awaitProvisioningRowLockWait(jdbc);

            releaseBlockedTransaction.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            Throwable provisioningFailure = provisioning.get(
                    10,
                    TimeUnit.SECONDS
            );

            assertThat(provisioningFailure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                            "Identidade indisponível para provisionamento."
                    );
            assertThat(jdbc.queryForObject("""
                    SELECT status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, String.class, collaboratorId))
                    .isEqualTo("BLOQUEADA");
            assertThat(jdbc.queryForObject("""
                    SELECT email_autenticacao
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, String.class, collaboratorId))
                    .isEqualTo("protected@example.invalid");
        } finally {
            releaseBlockedTransaction.countDown();
            executor.shutdownNow();
        }
    }

    private boolean claim(
            ProvisioningReceiptRepository receipts,
            DataSourceTransactionManager transactions,
            CyclicBarrier start
    ) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        Boolean claimed = new TransactionTemplate(transactions).execute(
                ignored -> receipts.claim("a".repeat(64), 2)
        );
        return Boolean.TRUE.equals(claimed);
    }

    private PdorMysqlTestDatabase migratedDatabase(String name) {
        PdorMysqlTestDatabase created = PdorMysqlTestDatabase.create(name);
        created.migrate();
        return created;
    }

    private String insertCollaborator(
            JdbcTemplate jdbc,
            String sourceId,
            String cpf
    ) {
        String collaboratorId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    cpf_hash, nome, papel_acesso, ativo
                ) VALUES (?, 'teste', 'teste', ?, ?,
                          'Colaborador Sintético', 'ALFA', 1)
                """,
                collaboratorId,
                sourceId,
                CpfHasher.hashDeDigitos(cpf)
        );
        return collaboratorId;
    }

    private void awaitProvisioningRowLockWait(JdbcTemplate jdbc)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waits = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits waiting
                    INNER JOIN performance_schema.data_locks requested
                        ON requested.engine_lock_id =
                           waiting.requesting_engine_lock_id
                    WHERE requested.object_schema = DATABASE()
                      AND requested.object_name = 'auth_identity'
                    """, Integer.class);
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Provisionamento não aguardou o lock de auth_identity."
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timeout na coordenação do teste concorrente."
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Teste concorrente interrompido.",
                    exception
            );
        }
    }

    private Path provisioningManifest() throws Exception {
        Path manifest = tempDir.resolve("provisioning-secret.json");
        Files.writeString(manifest, """
                {"version":1,
                 "nonce":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                 "identities":[
                   {"cpf":"11144477735","email":"alfa@example.invalid"},
                   {"cpf":"90000007935","email":"alfa@example.invalid"}
                 ]}
                """);
        Files.setPosixFilePermissions(manifest, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
        return manifest;
    }
}

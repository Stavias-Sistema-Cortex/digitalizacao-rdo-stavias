package com.projeto.cortex.obras;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ObraOperabilityGuard.class)
class ArchivedObraWriteGuardIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("archived_obra_write_guard");

    @Autowired
    private ObraOperabilityGuard guard;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("spring.datasource.driver-class-name", () ->
                "org.postgresql.Driver"
        );
    }

    @Test
    void allowsActiveAndInactiveWorksitesInsideTheCurrentTransaction() {
        String activeId = insertWorksite("ATIVA", false);
        String inactiveId = insertWorksite("INATIVA", false);

        guard.requireWritable(activeId);
        guard.requireWritable(inactiveId);
    }

    @Test
    void rejectsArchivedAndMissingWorksites() {
        String archivedId = insertWorksite("ATIVA", true);

        assertThatThrownBy(() -> guard.requireWritable(archivedId))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode())
                                    .isEqualTo(HttpStatus.NOT_FOUND);
                            assertThat(error.getReason())
                                    .isEqualTo("Obra não encontrada ou arquivada.");
                        }
                );
        assertThatThrownBy(() -> guard.requireWritable(UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND)
                );
    }

    @Test
    void restoreReenablesNewOperationalWrites() {
        String obraId = insertWorksite("INATIVA", true);
        jdbc.update(
                "UPDATE obra SET arquivado_em = NULL WHERE id = ?",
                obraId
        );

        guard.requireWritable(obraId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refusesToRunWithoutAnExistingTransaction() {
        String obraId = insertWorksite("ATIVA", false);

        assertThatThrownBy(() -> guard.requireWritable(obraId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void guardFailureRollsBackTheWholeOperationalMutation() {
        String obraId = insertWorksite("ATIVA", true);
        String originalName = jdbc.queryForObject(
                "SELECT nome FROM obra WHERE id = ?",
                String.class,
                obraId
        );
        TransactionTemplate transaction = new TransactionTemplate(
                transactionManager
        );

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update(
                    "UPDATE obra SET nome = 'não deve persistir' WHERE id = ?",
                    obraId
            );
            guard.requireWritable(obraId);
        })).isInstanceOf(ResponseStatusException.class);

        assertThat(jdbc.queryForObject(
                "SELECT nome FROM obra WHERE id = ?",
                String.class,
                obraId
        )).isEqualTo(originalName);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void caughtGuardRejectionDoesNotPoisonABatchTransaction() {
        String archivedId = insertWorksite("ATIVA", true);
        String writableId = insertWorksite("INATIVA", false);
        TransactionTemplate transaction = new TransactionTemplate(
                transactionManager
        );

        transaction.executeWithoutResult(status -> {
            try {
                guard.requireWritable(archivedId);
            } catch (ResponseStatusException expected) {
                assertThat(expected.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND);
            }
            guard.requireWritable(writableId);
            jdbc.update(
                    "UPDATE obra SET observacoes = 'batch continuou' WHERE id = ?",
                    writableId
            );
        });

        assertThat(jdbc.queryForObject(
                "SELECT observacoes FROM obra WHERE id = ?",
                String.class,
                writableId
        )).isEqualTo("batch continuou");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void archiveWinningTheRowLockPreventsAConcurrentChildWrite()
            throws Exception {
        String obraId = insertWorksite("ATIVA", false);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS archived_obra_guard_child (
                    id VARCHAR(36) PRIMARY KEY,
                    obra_id VARCHAR(36) NOT NULL REFERENCES obra(id)
                )
                """);
        CountDownLatch archiveHasRowLock = new CountDownLatch(1);
        CountDownLatch allowArchiveCommit = new CountDownLatch(1);
        TransactionTemplate archiveTransaction = new TransactionTemplate(
                transactionManager
        );
        TransactionTemplate childTransaction = new TransactionTemplate(
                transactionManager
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> archive = executor.submit(() ->
                    archiveTransaction.executeWithoutResult(status -> {
                        jdbc.queryForObject(
                                "SELECT id FROM obra WHERE id = ? FOR UPDATE",
                                String.class,
                                obraId
                        );
                        jdbc.update(
                                """
                                UPDATE obra
                                SET arquivado_em = CURRENT_TIMESTAMP
                                WHERE id = ?
                                """,
                                obraId
                        );
                        archiveHasRowLock.countDown();
                        await(allowArchiveCommit);
                    })
            );
            assertThat(archiveHasRowLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> childWrite = executor.submit(() ->
                    childTransaction.executeWithoutResult(status -> {
                        guard.requireWritable(obraId);
                        jdbc.update(
                                """
                                INSERT INTO archived_obra_guard_child (
                                    id, obra_id
                                ) VALUES (?, ?)
                                """,
                                UUID.randomUUID().toString(),
                                obraId
                        );
                    })
            );

            assertThatThrownBy(() -> childWrite.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowArchiveCommit.countDown();
            archive.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> childWrite.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            ExecutionException.class,
                            error -> assertThat(error.getCause())
                                    .isInstanceOf(ResponseStatusException.class)
                    );
        } finally {
            allowArchiveCommit.countDown();
        }

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM archived_obra_guard_child
                WHERE obra_id = ?
                """,
                Integer.class,
                obraId
        )).isZero();
    }

    private String insertWorksite(String status, boolean archived) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, fonte_criacao,
                    arquivado_em
                ) VALUES (?, ?, ?, ?, 'MANUAL', CASE WHEN ? THEN
                    CURRENT_TIMESTAMP ELSE NULL END)
                """,
                id,
                "GUARD-" + id,
                "Obra " + id,
                status,
                archived
        );
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout aguardando coordenação");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Coordenação interrompida",
                    exception
            );
        }
    }
}

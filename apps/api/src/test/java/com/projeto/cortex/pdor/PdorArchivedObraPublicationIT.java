package com.projeto.cortex.pdor;

import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ObraOperabilityGuard.class,
        PdorSnapshotPublicationService.class
})
class PdorArchivedObraPublicationIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("pdor_archived_obra_guard");

    @Autowired
    private PdorSnapshotPublicationService publicationService;

    @Autowired
    private ObraRepository obraRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private PdorSnapshotRepository snapshotRepository;

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
    void archivedWorksiteRemainsResolvableForHistoricalPdorReads() {
        String obraId = insertWorksite(true);

        assertThat(obraRepository.findByIdentificador(obraId))
                .extracting(obra -> obra.getId())
                .containsExactly(obraId);
        assertThat(obraRepository.findAtivasByIdentificador(obraId)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void archiveWinningTheRowLockPreventsPdorPublication() throws Exception {
        String obraId = insertWorksite(false);
        PdorSnapshot snapshot = mock(PdorSnapshot.class);
        when(snapshot.obraId()).thenReturn(obraId);
        when(snapshot.current()).thenReturn(false);
        CountDownLatch archiveHasRowLock = new CountDownLatch(1);
        CountDownLatch allowArchiveCommit = new CountDownLatch(1);
        TransactionTemplate archiveTransaction = new TransactionTemplate(
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

            Future<?> publication = executor.submit(() ->
                    publicationService.publish(snapshot, () -> {
                    })
            );

            assertThatThrownBy(() -> publication.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowArchiveCommit.countDown();
            archive.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> publication.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            ExecutionException.class,
                            error -> assertThat(error.getCause())
                                    .isInstanceOf(ResponseStatusException.class)
                    );
        } finally {
            allowArchiveCommit.countDown();
        }

        verify(snapshotRepository, never()).insert(snapshot);
        verify(snapshotRepository, never()).replaceCurrent(snapshot);
    }

    private String insertWorksite(boolean archived) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, fonte_criacao,
                    arquivado_em
                ) VALUES (?, ?, ?, 'ATIVA', 'MANUAL', CASE WHEN ? THEN
                    CURRENT_TIMESTAMP ELSE NULL END)
                """,
                id,
                "PDOR-GUARD-" + id,
                "Obra " + id,
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

package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RdoAllocationWorksiteScopeIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("rdo_allocation_scope");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migratePostgresql() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    /**
     * O vínculo deixou de decidir quem pode constar como mão de obra. Quem está
     * vinculado só a outra obra trabalhou nesta assim mesmo — remanejar gente
     * entre frentes é rotina, e o cadastro do vínculo costuma vir depois do
     * trabalho, não antes. Recusar não desfazia o dia: prendia o apontamento no
     * aparelho de quem estava em campo.
     */
    @Test
    void createAcceptsCollaboratorLinkedOnlyToAnotherWorksite() {
        String targetWorksiteId = id();
        String foreignWorksiteId = id();
        String collaboratorId = id();
        String rdoId = id();
        String allocationId = id();
        LocalDate date = LocalDate.of(2026, 7, 23);

        insertWorksite(targetWorksiteId, "Target create");
        insertWorksite(foreignWorksiteId, "Foreign create");
        insertCollaborator(collaboratorId, true, false);
        linkToWorksite(collaboratorId, foreignWorksiteId, "ATIVO");
        insertRdo(rdoId, targetWorksiteId, date);

        RdoOperationalDetailService service = service();

        transactions.executeWithoutResult(status -> service.substituirDetalhes(
                rdoId,
                targetWorksiteId,
                null,
                date,
                "DIURNO",
                List.of(),
                List.of(allocation(allocationId, collaboratorId))
        ));

        assertAllocationPresent(
                allocationId,
                collaboratorId,
                targetWorksiteId,
                rdoId
        );
    }

    /**
     * A troca de uma pessoa por outra de fora da obra também passou a valer, e
     * o método faz jus ao nome: substituir os detalhes deixa no RDO exatamente
     * quem o último envio declarou, sem sobra do anterior.
     */
    @Test
    void draftUpdateAcceptsReplacementFromAnotherWorksite() {
        String targetWorksiteId = id();
        String foreignWorksiteId = id();
        String originalCollaboratorId = id();
        String foreignCollaboratorId = id();
        String rdoId = id();
        String originalAllocationId = id();
        String replacementAllocationId = id();
        LocalDate date = LocalDate.of(2026, 7, 24);

        insertWorksite(targetWorksiteId, "Target update");
        insertWorksite(foreignWorksiteId, "Foreign update");
        insertCollaborator(originalCollaboratorId, true, false);
        insertCollaborator(foreignCollaboratorId, true, false);
        linkToWorksite(originalCollaboratorId, targetWorksiteId, "ATIVO");
        linkToWorksite(foreignCollaboratorId, foreignWorksiteId, "ATIVO");
        insertRdo(rdoId, targetWorksiteId, date);

        RdoOperationalDetailService service = service();
        transactions.executeWithoutResult(status -> service.substituirDetalhes(
                rdoId,
                targetWorksiteId,
                null,
                date,
                "DIURNO",
                List.of(),
                List.of(allocation(
                        originalAllocationId,
                        originalCollaboratorId
                ))
        ));

        transactions.executeWithoutResult(status -> service.substituirDetalhes(
                rdoId,
                targetWorksiteId,
                null,
                date,
                "DIURNO",
                List.of(),
                List.of(allocation(
                        replacementAllocationId,
                        foreignCollaboratorId
                ))
        ));

        assertAllocationPresent(
                replacementAllocationId,
                foreignCollaboratorId,
                targetWorksiteId,
                rdoId
        );
        assertAllocationAbsent(originalAllocationId);
    }

    @Test
    void carryForwardKeepsActiveTargetWorksiteCollaborator() {
        String targetWorksiteId = id();
        String collaboratorId = id();
        String rdoId = id();
        String allocationId = id();
        LocalDate date = LocalDate.of(2026, 7, 25);

        insertWorksite(targetWorksiteId, "Target carry forward");
        insertCollaborator(collaboratorId, true, false);
        linkToWorksite(collaboratorId, targetWorksiteId, "ATIVO");
        insertRdo(rdoId, targetWorksiteId, date);

        RdoOperationalDetailService service = service();
        RdoOperationalDetailService.RdoOperationalDetails created =
                transactions.execute(status -> service.substituirDetalhes(
                        rdoId,
                        targetWorksiteId,
                        null,
                        date,
                        "DIURNO",
                        List.of(),
                        List.of(allocation(allocationId, collaboratorId))
                ));

        assertThat(created).isNotNull();
        assertThat(created.alocacoesColaboradores())
                .extracting(RdoResponse.AlocacaoColaboradorItem::colaboradorId)
                .containsExactly(collaboratorId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM alocacao_colaborador
                WHERE id = ?
                  AND colaborador_id = ?
                  AND obra_id = ?
                """, Integer.class, allocationId, collaboratorId,
                targetWorksiteId))
                .isEqualTo(1);
    }

    /**
     * O caso que originou a mudança. O vínculo pode ter sido revogado enquanto
     * o aparelho estava sem rede — a pessoa trabalhou naquele dia assim mesmo, e
     * o diário registra o que aconteceu, não quem a obra reconhece hoje.
     */
    @Test
    void acceptsCollaboratorWhoseMembershipWasRevoked() {
        String targetWorksiteId = id();
        String collaboratorId = id();
        String rdoId = id();
        String allocationId = id();
        LocalDate date = LocalDate.of(2026, 7, 26);

        insertWorksite(targetWorksiteId, "Target revoked");
        insertCollaborator(collaboratorId, true, false);
        linkToWorksite(collaboratorId, targetWorksiteId, "REVOGADO");
        insertRdo(rdoId, targetWorksiteId, date);

        RdoOperationalDetailService service = service();

        transactions.executeWithoutResult(status -> service.substituirDetalhes(
                rdoId,
                targetWorksiteId,
                null,
                date,
                "DIURNO",
                List.of(),
                List.of(allocation(allocationId, collaboratorId))
        ));

        assertAllocationPresent(
                allocationId,
                collaboratorId,
                targetWorksiteId,
                rdoId
        );
    }

    @Test
    void oppositePayloadOrdersCompleteWithoutDeadlock() throws Exception {
        String worksiteId = id();
        String collaboratorA = id();
        String collaboratorB = id();
        String firstRdoId = id();
        String secondRdoId = id();
        LocalDate firstDate = LocalDate.of(2026, 7, 27);
        LocalDate secondDate = firstDate.plusDays(1);

        insertWorksite(worksiteId, "Deterministic allocation locks");
        insertCollaborator(collaboratorA, true, false);
        insertCollaborator(collaboratorB, true, false);
        linkToWorksite(collaboratorA, worksiteId, "ATIVO");
        linkToWorksite(collaboratorB, worksiteId, "ATIVO");
        insertRdo(firstRdoId, worksiteId, firstDate);
        insertRdo(secondRdoId, worksiteId, secondDate);

        CoordinatedFirstCollaboratorLockJdbcTemplate coordinatedJdbc =
                new CoordinatedFirstCollaboratorLockJdbcTemplate(dataSource);
        RdoOperationalDetailService service = service(coordinatedJdbc);
        TransactionTemplate coordinatedTransactions = transactions(dataSource);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() ->
                    coordinatedTransactions.executeWithoutResult(status -> {
                        coordinatedJdbc.execute(
                                "SET LOCAL deadlock_timeout = '100ms'"
                        );
                        service.substituirDetalhes(
                                firstRdoId,
                                worksiteId,
                                null,
                                firstDate,
                                "DIURNO",
                                List.of(),
                                List.of(
                                        allocation(id(), collaboratorA),
                                        allocation(id(), collaboratorB)
                                )
                        );
                    })
            );
            Future<?> second = executor.submit(() ->
                    coordinatedTransactions.executeWithoutResult(status -> {
                        coordinatedJdbc.execute(
                                "SET LOCAL deadlock_timeout = '100ms'"
                        );
                        service.substituirDetalhes(
                                secondRdoId,
                                worksiteId,
                                null,
                                secondDate,
                                "DIURNO",
                                List.of(),
                                List.of(
                                        allocation(id(), collaboratorB),
                                        allocation(id(), collaboratorA)
                                )
                        );
                    })
            );

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM alocacao_colaborador
                WHERE rdo_id IN (?, ?)
                """, Integer.class, firstRdoId, secondRdoId))
                .isEqualTo(4);
    }

    /**
     * A corrida continua importando, só mudou de alvo. O vínculo saiu da
     * exigência, mas o colaborador ativo não: ele é o que garante que existe
     * alguém a quem atribuir o trabalho. A desativação que ganha a corrida por
     * um instante ainda tem de vencer — a validação toma o mesmo registro em
     * FOR UPDATE, espera o commit de quem chegou antes e relê o que ficou. Sem
     * essa espera, a alocação leria o valor velho e gravaria mão de obra que o
     * cadastro já tinha desligado.
     */
    @Test
    void concurrentDeactivationWinsBeforeValidationAndFailsClosed()
            throws Exception {
        String worksiteId = id();
        String collaboratorId = id();
        String rdoId = id();
        String allocationId = id();
        LocalDate date = LocalDate.of(2026, 7, 29);

        insertWorksite(worksiteId, "Concurrent deactivation");
        insertCollaborator(collaboratorId, true, false);
        linkToWorksite(collaboratorId, worksiteId, "ATIVO");
        insertRdo(rdoId, worksiteId, date);

        CountDownLatch deactivationLocked = new CountDownLatch(1);
        CountDownLatch allowDeactivationCommit = new CountDownLatch(1);
        CountDownLatch collaboratorValidationStarted = new CountDownLatch(1);
        SignallingCollaboratorQueryJdbcTemplate signallingJdbc =
                new SignallingCollaboratorQueryJdbcTemplate(
                        dataSource,
                        collaboratorValidationStarted
                );
        RdoOperationalDetailService service = service(signallingJdbc);
        TransactionTemplate signallingTransactions = transactions(dataSource);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deactivation = executor.submit(() ->
                    transactions.executeWithoutResult(status -> {
                        jdbc.update("""
                                UPDATE colaborador
                                SET ativo = FALSE
                                WHERE id = ?
                                """, collaboratorId);
                        deactivationLocked.countDown();
                        await(allowDeactivationCommit);
                    })
            );
            assertThat(deactivationLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> allocation = executor.submit(() ->
                    signallingTransactions.executeWithoutResult(status ->
                            service.substituirDetalhes(
                                    rdoId,
                                    worksiteId,
                                    null,
                                    date,
                                    "DIURNO",
                                    List.of(),
                                    List.of(allocation(
                                            allocationId,
                                            collaboratorId
                                    ))
                            )
                    )
            );

            assertThat(
                    collaboratorValidationStarted.await(5, TimeUnit.SECONDS)
            ).isTrue();
            awaitCollaboratorValidationRowLock(allocation);
            allowDeactivationCommit.countDown();
            deactivation.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() -> allocation.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining(
                            "Colaborador inativo não pode ser alocado."
                    );
        } finally {
            allowDeactivationCommit.countDown();
        }

        assertAllocationAbsent(allocationId);
        assertAllocationMemoryAbsent(allocationId);
        assertThat(jdbc.queryForObject("""
                SELECT ativo
                FROM colaborador
                WHERE id = ?
                """, Boolean.class, collaboratorId))
                .isFalse();
    }

    private static RdoOperationalDetailService service() {
        return service(jdbc);
    }

    private static RdoOperationalDetailService service(
            JdbcTemplate operationalJdbc
    ) {
        CortexOperationalMemoryService memory =
                new CortexOperationalMemoryService(
                        operationalJdbc,
                        new ObjectMapper().findAndRegisterModules(),
                        mock(ApplicationEventPublisher.class)
                );
        return new RdoOperationalDetailService(operationalJdbc, memory);
    }

    private static TransactionTemplate transactions(
            DriverManagerDataSource transactionalDataSource
    ) {
        return new TransactionTemplate(
                new DataSourceTransactionManager(transactionalDataSource)
        );
    }

    private static RdoCreateRequest.AlocacaoColaboradorItem allocation(
            String allocationId,
            String collaboratorId
    ) {
        return new RdoCreateRequest.AlocacaoColaboradorItem(
                allocationId,
                collaboratorId,
                "Equipe",
                null,
                LocalTime.of(8, 0),
                LocalTime.of(12, 0),
                new BigDecimal("0.500000"),
                "DIURNO",
                "OPERACIONAL",
                null,
                "TRABALHO",
                "RDO",
                "REGISTRADA",
                "scope regression"
        );
    }

    private static void insertWorksite(String worksiteId, String name) {
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                worksiteId,
                "CTR-" + worksiteId,
                name
        );
    }

    private static void insertCollaborator(
            String collaboratorId,
            boolean active,
            boolean deleted
    ) {
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    codigo_colaborador, nome, papel_acesso, ativo, deletado_em
                ) VALUES (?, 'scope-test', 'colaborador', ?, ?, ?,
                          'BETA', ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """,
                collaboratorId,
                collaboratorId,
                collaboratorId.substring(0, 8),
                "Collaborator " + collaboratorId.substring(0, 8),
                active,
                deleted
        );
    }

    private static void linkToWorksite(
            String collaboratorId,
            String worksiteId,
            String status
    ) {
        jdbc.update("""
                INSERT INTO vinculo_colaborador_obra (
                    id, obra_id, colaborador_id, status, papel_na_obra
                ) VALUES (?, ?, ?, ?, 'OPERACIONAL')
                """, id(), worksiteId, collaboratorId, status);
    }

    private static void insertRdo(
            String rdoId,
            String worksiteId,
            LocalDate date
    ) {
        jdbc.update("""
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo, status
                ) VALUES (?, ?, ?, ?, 'RASCUNHO')
                """, rdoId, worksiteId, "RDO-" + rdoId, date);
    }

    private static void assertAllocationPresent(
            String allocationId,
            String collaboratorId,
            String worksiteId,
            String rdoId
    ) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM alocacao_colaborador
                WHERE id = ?
                  AND colaborador_id = ?
                  AND obra_id = ?
                  AND rdo_id = ?
                """, Integer.class, allocationId, collaboratorId, worksiteId,
                rdoId))
                .isEqualTo(1);
    }

    private static void assertAllocationAbsent(String allocationId) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM alocacao_colaborador
                WHERE id = ?
                """, Integer.class, allocationId))
                .isZero();
    }

    private static void assertAllocationMemoryAbsent(String allocationId) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM cortex_objeto
                WHERE tipo_entidade = 'ALOCACAO_COLABORADOR'
                  AND entidade_id = ?
                """, Integer.class, allocationId))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM cortex_relacao
                WHERE origem_tipo = 'ALOCACAO_COLABORADOR'
                  AND origem_id = ?
                """, Integer.class, allocationId))
                .isZero();
    }

    private static void awaitCollaboratorValidationRowLock(
            Future<?> allocation
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (allocation.isDone()) {
                return;
            }
            Integer waits = jdbc.queryForObject("""
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid()
                      AND query LIKE '%FROM colaborador%FOR UPDATE%'
                      AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "RDO validation did not wait for the collaborator row lock."
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class CoordinatedFirstCollaboratorLockJdbcTemplate
            extends JdbcTemplate {

        private final CountDownLatch firstQueriesStarted =
                new CountDownLatch(2);
        private final CountDownLatch distinctRowsLocked =
                new CountDownLatch(2);
        private final Set<Long> interceptedThreads =
                ConcurrentHashMap.newKeySet();
        private final Map<Long, String> firstCollaboratorByThread =
                new ConcurrentHashMap<>();

        private CoordinatedFirstCollaboratorLockJdbcTemplate(
                DriverManagerDataSource coordinatedDataSource
        ) {
            super(coordinatedDataSource);
        }

        @Override
        public <T> List<T> query(
                String sql,
                RowMapper<T> rowMapper,
                Object... args
        ) {
            long threadId = Thread.currentThread().threadId();
            boolean firstCollaboratorLock =
                    isCollaboratorLock(sql)
                            && interceptedThreads.add(threadId);
            if (!firstCollaboratorLock) {
                return super.query(sql, rowMapper, args);
            }

            firstCollaboratorByThread.put(threadId, args[0].toString());
            firstQueriesStarted.countDown();
            await(firstQueriesStarted);

            List<T> rows = super.query(sql, rowMapper, args);
            if (new HashSet<>(firstCollaboratorByThread.values()).size() > 1) {
                distinctRowsLocked.countDown();
                await(distinctRowsLocked);
            }
            return rows;
        }

        private static boolean isCollaboratorLock(String sql) {
            return sql.contains("FROM colaborador")
                    && sql.contains("FOR UPDATE");
        }
    }

    private static final class SignallingCollaboratorQueryJdbcTemplate
            extends JdbcTemplate {

        private final CountDownLatch validationStarted;

        private SignallingCollaboratorQueryJdbcTemplate(
                DriverManagerDataSource signallingDataSource,
                CountDownLatch validationStarted
        ) {
            super(signallingDataSource);
            this.validationStarted = validationStarted;
        }

        @Override
        public <T> List<T> query(
                String sql,
                RowMapper<T> rowMapper,
                Object... args
        ) {
            if (sql.contains("FROM colaborador")
                    && sql.contains("FOR UPDATE")) {
                validationStarted.countDown();
            }
            return super.query(sql, rowMapper, args);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}

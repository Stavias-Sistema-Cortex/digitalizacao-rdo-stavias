package com.projeto.cortex.colaboradores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.integracoes.AcademyUserSnapshot;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlAcademyImportAtomicityIT {

    private static final String FIRST_CPF = "111.444.777-35";
    private static final String SECOND_CPF = "900.000.079-35";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("academy_import_atomicity_it");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

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
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanImportState() {
        jdbc.update("DELETE FROM cortex_evidencia_operacional");
        jdbc.update("DELETE FROM cortex_mapeamento_legado");
        jdbc.update("DELETE FROM cortex_estado_entidade");
        jdbc.update("DELETE FROM cortex_evento_operacional");
        jdbc.update("DELETE FROM cortex_objeto");
        jdbc.update("""
                UPDATE cortex_evento_commit_sequence
                SET ultima_commit_seq = 0
                WHERE id = 1
                """);
        jdbc.update("DELETE FROM auth_identity");
        jdbc.update("DELETE FROM colaborador");
        jdbc.update("""
                DELETE FROM source_sync_checkpoint
                WHERE connector_name = 'acad_colaborador_import'
                """);
        jdbc.update("""
                DELETE FROM source_sync_run
                WHERE connector_name = 'acad_colaborador_import'
                """);
    }

    @Test
    void lateWriteFailureRollsBackDomainIdentityCheckpointAndSuccess() {
        JdbcTemplate failingJdbc = new CheckpointFailingJdbcTemplate(
                dataSource
        );
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        AcademyUserSnapshot snapshot = AcademyUserSnapshot.complete(List.of(
                academyUser(
                        920_101,
                        FIRST_CPF,
                        "first.rollback@example.invalid",
                        true
                ),
                academyUser(
                        920_102,
                        SECOND_CPF,
                        "second.rollback@example.invalid",
                        true
                )
        ));
        when(academy.fetchCompleteSnapshot(anyInt())).thenAnswer(ignored -> {
            assertThat(failingJdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM source_sync_run
                    WHERE connector_name = 'acad_colaborador_import'
                      AND status = 'RUNNING'
                    """, Integer.class)).isOne();
            return snapshot;
        });
        AuthIdentityRepository identities = new AuthIdentityRepository(
                failingJdbc,
                new HmacCpfLookupDigestService(
                        "academy-atomicity-test",
                        null,
                        "test-only-academy-atomicity-hmac-material-0001",
                        null
                )
        );
        ColaboradorImportService service = service(
                failingJdbc,
                academy,
                identities,
                realMemory(failingJdbc),
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM colaborador
                WHERE banco_origem = 'dbstavias_acad'
                  AND tabela_origem = 'usuarios'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_identity",
                Integer.class
        )).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM source_sync_checkpoint
                WHERE connector_name = 'acad_colaborador_import'
                """, Integer.class)).isZero();

        Map<String, Object> run = jdbc.queryForMap("""
                SELECT
                    status,
                    records_read,
                    records_inserted,
                    records_updated,
                    records_deactivated,
                    error_message
                FROM source_sync_run
                WHERE connector_name = 'acad_colaborador_import'
                """);
        assertThat(run)
                .containsEntry("status", "FAILED")
                .containsEntry("records_read", 2)
                .containsEntry("records_inserted", 0)
                .containsEntry("records_updated", 0)
                .containsEntry("records_deactivated", 0)
                .containsEntry(
                        "error_message",
                        "Falha ao aplicar snapshot Academy."
                );
        assertThat(run.toString())
                .doesNotContain(FIRST_CPF)
                .doesNotContain(SECOND_CPF)
                .doesNotContain("rollback@example.invalid");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM cortex_objeto
                WHERE tipo_entidade = 'COLABORADOR'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM cortex_evidencia_operacional
                WHERE tipo_entidade = 'COLABORADOR'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM cortex_mapeamento_legado
                WHERE tipo_entidade = 'COLABORADOR'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM cortex_evento_operacional
                WHERE tipo_entidade = 'COLABORADOR'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM cortex_estado_entidade
                WHERE tipo_entidade = 'COLABORADOR'
                """, Integer.class)).isZero();
    }

    @Test
    void completeSnapshotHonorsMissingGraceAndExplicitInactiveImmediately() {
        LocalDateTime now = LocalDateTime.now();
        seedCollaborator(920_201, now.minusHours(23));
        seedCollaborator(920_202, now.minusHours(25));
        seedCollaborator(920_203, now.minusHours(1));

        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                920_203,
                                FIRST_CPF,
                                "explicit.inactive@example.invalid",
                                false
                        )
                ))
        );
        AuthIdentityRepository identities = identities(jdbc);
        upsertAcademyIdentity(
                identities,
                AcademyCollaboratorIdentity.fromAcademyUserId(920_202),
                SECOND_CPF,
                "historical.absent@example.invalid"
        );
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities,
                24
        );

        ColaboradorImportResult result =
                service.importarUsuariosDaAcademy();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.registrosDesativados()).isOne();
        assertCollaboratorState(920_201, true, false);
        assertCollaboratorState(920_202, false, true);
        assertCollaboratorState(920_203, false, false);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auth_identity
                WHERE colaborador_id = ?
                  AND status = 'PENDENTE'
                  AND cpf_lookup_hmac IS NULL
                  AND cpf_lookup_key_id IS NULL
                  AND email_autenticacao IS NULL
                """, Integer.class,
                AcademyCollaboratorIdentity.fromAcademyUserId(920_202)
        )).isOne();
        assertThat(identities.findActiveAcademyByCpf(SECOND_CPF)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT records_deactivated
                FROM source_sync_run
                WHERE connector_name = 'acad_colaborador_import'
                  AND status = 'SUCCESS'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM source_sync_checkpoint
                WHERE connector_name = 'acad_colaborador_import'
                  AND last_success_at IS NOT NULL
                  AND last_error_at IS NULL
                """, Integer.class)).isOne();
    }

    @Test
    void explicitInactiveAcademyOwnerReleasesCpfForNewActiveOwner() {
        int oldSourceId = 920_211;
        int newSourceId = 920_212;
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                oldSourceId,
                                FIRST_CPF,
                                "reassigned.explicit@example.invalid",
                                true
                        )
                )),
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                oldSourceId,
                                FIRST_CPF,
                                "reassigned.explicit@example.invalid",
                                false
                        ),
                        academyUser(
                                newSourceId,
                                FIRST_CPF,
                                "reassigned.explicit@example.invalid",
                                true
                        )
                ))
        );
        AuthIdentityRepository identities = identities(jdbc);
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities,
                24
        );

        service.importarUsuariosDaAcademy();
        ColaboradorImportResult transfer =
                service.importarUsuariosDaAcademy();

        assertThat(transfer.status()).isEqualTo("SUCCESS");
        assertCollaboratorState(oldSourceId, false, false);
        assertCollaboratorState(newSourceId, true, false);
        assertThat(identities.findActiveByCpf(FIRST_CPF))
                .get()
                .extracting(
                        com.projeto.cortex.auth.identity.AuthIdentity
                                ::colaboradorId
                )
                .isEqualTo(
                        AcademyCollaboratorIdentity.fromAcademyUserId(
                                newSourceId
                        )
                );
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auth_identity
                WHERE colaborador_id = ?
                  AND status = 'PENDENTE'
                  AND cpf_lookup_hmac IS NULL
                  AND cpf_lookup_key_id IS NULL
                  AND email_autenticacao IS NULL
                """,
                Integer.class,
                AcademyCollaboratorIdentity.fromAcademyUserId(
                        oldSourceId
                )
        )).isOne();
    }

    @Test
    void blockedInactiveOwnerKeepsCpfReservationAndRejectsRecreation() {
        int blockedSourceId = 920_215;
        int recreatedSourceId = 920_216;
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                blockedSourceId,
                                FIRST_CPF,
                                "blocked.owner@example.invalid",
                                true
                        )
                )),
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                blockedSourceId,
                                FIRST_CPF,
                                "blocked.owner@example.invalid",
                                false
                        )
                )),
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                recreatedSourceId,
                                FIRST_CPF,
                                "recreated.owner@example.invalid",
                                true
                        )
                ))
        );
        AuthIdentityRepository identities = identities(jdbc);
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities,
                24
        );

        service.importarUsuariosDaAcademy();
        String blockedId =
                AcademyCollaboratorIdentity.fromAcademyUserId(
                        blockedSourceId
                );
        jdbc.update("""
                UPDATE auth_identity
                SET status = 'BLOQUEADA'
                WHERE colaborador_id = ?
                """, blockedId);
        Map<String, Object> before = jdbc.queryForMap("""
                SELECT
                    colaborador.cpf_hash,
                    identity.cpf_lookup_key_id,
                    identity.cpf_lookup_hmac
                FROM colaborador
                INNER JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.id = ?
                """, blockedId);

        assertThat(service.importarUsuariosDaAcademy().status())
                .isEqualTo("SUCCESS");
        assertCollaboratorState(blockedSourceId, false, false);
        Map<String, Object> inactive = jdbc.queryForMap("""
                SELECT
                    colaborador.cpf_hash,
                    identity.cpf_lookup_key_id,
                    identity.cpf_lookup_hmac,
                    identity.status
                FROM colaborador
                INNER JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.id = ?
                """, blockedId);
        assertThat(inactive)
                .containsEntry("cpf_hash", before.get("cpf_hash"))
                .containsEntry(
                        "cpf_lookup_key_id",
                        before.get("cpf_lookup_key_id")
                )
                .containsEntry(
                        "cpf_lookup_hmac",
                        before.get("cpf_lookup_hmac")
                )
                .containsEntry("status", "BLOQUEADA");

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();
        assertThat(identities.findActiveByCpf(FIRST_CPF)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM colaborador
                WHERE pk_origem = ?
                """,
                Integer.class,
                String.valueOf(recreatedSourceId)
        )).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT
                    colaborador.cpf_hash,
                    identity.cpf_lookup_key_id,
                    identity.cpf_lookup_hmac,
                    identity.status
                FROM colaborador
                INNER JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.id = ?
                """, blockedId))
                .containsEntry("cpf_hash", before.get("cpf_hash"))
                .containsEntry(
                        "cpf_lookup_key_id",
                        before.get("cpf_lookup_key_id")
                )
                .containsEntry(
                        "cpf_lookup_hmac",
                        before.get("cpf_lookup_hmac")
                )
                .containsEntry("status", "BLOQUEADA");
    }

    @Test
    void staleMissingAcademyOwnerReleasesCpfAfterGrace() {
        int oldSourceId = 920_221;
        int newSourceId = 920_222;
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                oldSourceId,
                                SECOND_CPF,
                                "old.missing@example.invalid",
                                true
                        )
                )),
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                newSourceId,
                                SECOND_CPF,
                                "new.missing@example.invalid",
                                true
                        )
                ))
        );
        AuthIdentityRepository identities = identities(jdbc);
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities,
                24
        );

        service.importarUsuariosDaAcademy();
        jdbc.update("""
                UPDATE colaborador
                SET visto_por_ultimo_em =
                    CURRENT_TIMESTAMP(6) - INTERVAL '25 hours'
                WHERE id = ?
                """,
                AcademyCollaboratorIdentity.fromAcademyUserId(
                        oldSourceId
                )
        );
        ColaboradorImportResult transfer =
                service.importarUsuariosDaAcademy();

        assertThat(transfer.status()).isEqualTo("SUCCESS");
        assertThat(transfer.registrosDesativados()).isOne();
        assertCollaboratorState(oldSourceId, false, true);
        assertCollaboratorState(newSourceId, true, false);
        assertThat(identities.findActiveByCpf(SECOND_CPF))
                .get()
                .extracting(
                        com.projeto.cortex.auth.identity.AuthIdentity
                                ::colaboradorId
                )
                .isEqualTo(
                        AcademyCollaboratorIdentity.fromAcademyUserId(
                                newSourceId
                        )
                );
    }

    @Test
    void crossReplicaLockRejectsOverlapBeforeSourceReadAndPreservesOrder()
            throws Exception {
        CountDownLatch oldSnapshotStarted = new CountDownLatch(1);
        CountDownLatch releaseOldSnapshot = new CountDownLatch(1);
        AcademySourceAdapter oldSource = mock(AcademySourceAdapter.class);
        AcademySourceAdapter newSource = mock(AcademySourceAdapter.class);
        when(oldSource.fetchCompleteSnapshot(anyInt())).thenAnswer(ignored -> {
            oldSnapshotStarted.countDown();
            if (!releaseOldSnapshot.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("synthetic latch timeout");
            }
            return AcademyUserSnapshot.complete(List.of(
                    academyUser(
                            920_301,
                            FIRST_CPF,
                            "old.snapshot@example.invalid",
                            true,
                            "Snapshot antigo"
                    )
            ));
        });
        when(newSource.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                920_301,
                                FIRST_CPF,
                                "new.snapshot@example.invalid",
                                true,
                                "Snapshot novo"
                        )
                ))
        );

        ColaboradorImportService oldReplica = service(
                jdbc,
                oldSource,
                identities(jdbc),
                24
        );
        ColaboradorImportService newReplica = service(
                jdbc,
                newSource,
                identities(jdbc),
                24
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ColaboradorImportResult> oldRun =
                    executor.submit(oldReplica::importarUsuariosDaAcademy);
            assertThat(oldSnapshotStarted.await(10, TimeUnit.SECONDS))
                    .isTrue();

            Throwable overlap = catchThrowable(
                    newReplica::importarUsuariosDaAcademy
            );
            releaseOldSnapshot.countDown();
            assertThat(oldRun.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo("SUCCESS");

            assertThat(overlap)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Falha ao importar colaboradores da Academy.")
                    .hasNoCause();
            verify(newSource, never()).fetchCompleteSnapshot(anyInt());

            assertThat(newReplica.importarUsuariosDaAcademy().status())
                    .isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("""
                    SELECT nome
                    FROM colaborador
                    WHERE banco_origem = 'dbstavias_acad'
                      AND tabela_origem = 'usuarios'
                      AND pk_origem = '920301'
                    """, String.class)).isEqualTo("Snapshot novo");
        } finally {
            releaseOldSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void requiresNewRunAndApplySurviveAnOuterTransactionRollback() {
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                920_401,
                                FIRST_CPF,
                                "outer.success@example.invalid",
                                true
                        )
                ))
        );
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities(jdbc),
                24
        );

        TransactionTemplate outer = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        outer.execute(status -> {
            assertThat(service.importarUsuariosDaAcademy().status())
                    .isEqualTo("SUCCESS");
            status.setRollbackOnly();
            return null;
        });

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM colaborador
                WHERE pk_origem = '920401'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM source_sync_run
                WHERE connector_name = 'acad_colaborador_import'
                  AND status = 'SUCCESS'
                """, Integer.class)).isOne();
    }

    @Test
    void requiresNewFailedRunSurvivesAnOuterTransactionRollback() {
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenThrow(
                new IllegalStateException(
                        "source detail: " + FIRST_CPF
                )
        );
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities(jdbc),
                24
        );

        TransactionTemplate outer = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        outer.execute(status -> {
            assertThatThrownBy(service::importarUsuariosDaAcademy)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage(
                            "Falha ao importar colaboradores da Academy."
                    )
                    .hasNoCause();
            status.setRollbackOnly();
            return null;
        });

        Map<String, Object> run = jdbc.queryForMap("""
                SELECT status, error_message
                FROM source_sync_run
                WHERE connector_name = 'acad_colaborador_import'
                """);
        assertThat(run)
                .containsEntry("status", "FAILED")
                .containsEntry(
                        "error_message",
                        "Falha ao ler snapshot completo da Academy."
                );
        assertThat(run.toString()).doesNotContain(FIRST_CPF);
    }

    @Test
    void effectiveManualEmailConflictIsRejectedBeforeAnyDomainOrMemoryWrite() {
        int firstSourceId = 920_501;
        int secondSourceId = 920_502;
        seedCollaborator(firstSourceId, LocalDateTime.now());
        AuthIdentityRepository identities = identities(jdbc);
        upsertAcademyIdentity(
                identities,
                AcademyCollaboratorIdentity.fromAcademyUserId(firstSourceId),
                FIRST_CPF,
                "initial.academy@example.invalid"
        );
        jdbc.update("""
                UPDATE auth_identity
                SET email_autenticacao = 'manual.owner@example.invalid',
                    email_fonte = 'MANUAL_VERIFICADO',
                    email_verificado_em = CURRENT_TIMESTAMP(6)
                WHERE colaborador_id = ?
                """,
                AcademyCollaboratorIdentity.fromAcademyUserId(firstSourceId)
        );

        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                firstSourceId,
                                FIRST_CPF,
                                "academy.changed@example.invalid",
                                true
                        ),
                        academyUser(
                                secondSourceId,
                                SECOND_CPF,
                                "manual.owner@example.invalid",
                                true
                        )
                ))
        );
        RecordingJdbcTemplate recordingJdbc =
                new RecordingJdbcTemplate(dataSource);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        ColaboradorImportService service = service(
                recordingJdbc,
                academy,
                identities(recordingJdbc),
                memory,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();

        assertThat(recordingJdbc.domainWriteSql()).isEmpty();
        verify(memory, never()).registrarObjeto(
                org.mockito.ArgumentMatchers.eq("COLABORADOR"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM colaborador
                WHERE pk_origem = '920502'
                """, Integer.class)).isZero();
    }

    @Test
    void persistedEmailOwnerSwapIsRejectedBeforeAnyDomainOrMemoryWrite() {
        int firstSourceId = 920_551;
        int secondSourceId = 920_552;
        seedCollaborator(firstSourceId, LocalDateTime.now());
        seedCollaborator(secondSourceId, LocalDateTime.now());
        AuthIdentityRepository identities = identities(jdbc);
        upsertAcademyIdentity(
                identities,
                AcademyCollaboratorIdentity.fromAcademyUserId(firstSourceId),
                FIRST_CPF,
                "first.email.owner@example.invalid"
        );
        upsertAcademyIdentity(
                identities,
                AcademyCollaboratorIdentity.fromAcademyUserId(secondSourceId),
                SECOND_CPF,
                "second.email.owner@example.invalid"
        );

        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                firstSourceId,
                                FIRST_CPF,
                                "second.email.owner@example.invalid",
                                true
                        ),
                        academyUser(
                                secondSourceId,
                                SECOND_CPF,
                                "first.email.owner@example.invalid",
                                true
                        )
                ))
        );
        RecordingJdbcTemplate recordingJdbc =
                new RecordingJdbcTemplate(dataSource);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        ColaboradorImportService service = service(
                recordingJdbc,
                academy,
                identities(recordingJdbc),
                memory,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();

        assertThat(recordingJdbc.domainWriteSql()).isEmpty();
        verify(memory, never()).registrarObjeto(
                org.mockito.ArgumentMatchers.eq("COLABORADOR"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void previousHmacOwnerIsRejectedBeforeAnyDomainOrMemoryWrite() {
        int ownerSourceId = 920_561;
        int candidateSourceId = 920_562;
        seedCollaborator(ownerSourceId, LocalDateTime.now());
        HmacCpfLookupDigestService digests =
                rotatingIdentityDigests();
        CpfLookupDigest previous =
                digests.candidates(FIRST_CPF).get(1);
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    cpf_lookup_hmac,
                    cpf_lookup_key_id,
                    email_autenticacao,
                    email_fonte,
                    status
                ) VALUES (?, ?, ?, ?, 'ACADEMY', 'ATIVA')
                """,
                AcademyCollaboratorIdentity.fromAcademyUserId(
                        ownerSourceId
                ),
                previous.value(),
                previous.keyId(),
                "previous.owner@example.invalid"
        );

        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                candidateSourceId,
                                FIRST_CPF,
                                "candidate@example.invalid",
                                true
                        )
                ))
        );
        RecordingJdbcTemplate recordingJdbc =
                new RecordingJdbcTemplate(dataSource);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        ColaboradorImportService service = service(
                recordingJdbc,
                academy,
                new AuthIdentityRepository(recordingJdbc, digests),
                memory,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();
        assertThat(recordingJdbc.domainWriteSql()).isEmpty();
        verify(memory, never()).registrarObjeto(
                org.mockito.ArgumentMatchers.eq("COLABORADOR"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void legacyCpfOwnerIsRejectedBeforeAnyDomainOrMemoryWrite() {
        int ownerSourceId = 920_571;
        int candidateSourceId = 920_572;
        seedCollaborator(ownerSourceId, LocalDateTime.now());
        jdbc.update("""
                UPDATE colaborador
                SET cpf_hash = ?
                WHERE id = ?
                """,
                CpfHasher.hashDeDigitos(FIRST_CPF.replaceAll("\\D", "")),
                AcademyCollaboratorIdentity.fromAcademyUserId(
                        ownerSourceId
                )
        );

        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                candidateSourceId,
                                FIRST_CPF,
                                "legacy.candidate@example.invalid",
                                true
                        )
                ))
        );
        RecordingJdbcTemplate recordingJdbc =
                new RecordingJdbcTemplate(dataSource);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        ColaboradorImportService service = service(
                recordingJdbc,
                academy,
                identities(recordingJdbc),
                memory,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();
        assertThat(recordingJdbc.domainWriteSql()).isEmpty();
        verify(memory, never()).registrarObjeto(
                org.mockito.ArgumentMatchers.eq("COLABORADOR"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void persistedCpfOwnerSwapIsRejectedBeforeAnyDomainOrMemoryWrite() {
        int firstSourceId = 920_601;
        int secondSourceId = 920_602;
        seedCollaborator(firstSourceId, LocalDateTime.now());
        seedCollaborator(secondSourceId, LocalDateTime.now());
        AuthIdentityRepository identities = identities(jdbc);
        upsertAcademyIdentity(
                identities,
                AcademyCollaboratorIdentity.fromAcademyUserId(firstSourceId),
                FIRST_CPF,
                "first.owner@example.invalid"
        );
        upsertAcademyIdentity(
                identities,
                AcademyCollaboratorIdentity.fromAcademyUserId(secondSourceId),
                SECOND_CPF,
                "second.owner@example.invalid"
        );

        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                firstSourceId,
                                SECOND_CPF,
                                "first.owner@example.invalid",
                                true
                        ),
                        academyUser(
                                secondSourceId,
                                FIRST_CPF,
                                "second.owner@example.invalid",
                                true
                        )
                ))
        );
        RecordingJdbcTemplate recordingJdbc =
                new RecordingJdbcTemplate(dataSource);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        ColaboradorImportService service = service(
                recordingJdbc,
                academy,
                identities(recordingJdbc),
                memory,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();

        assertThat(recordingJdbc.domainWriteSql()).isEmpty();
        verify(memory, never()).registrarObjeto(
                org.mockito.ArgumentMatchers.eq("COLABORADOR"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void cpfOnlyChangePersistsAndIncrementsVersionWithStableSourceHash() {
        int sourceId = 920_701;
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                sourceId,
                                FIRST_CPF,
                                "cpf.change@example.invalid",
                                true
                        )
                )),
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                sourceId,
                                SECOND_CPF,
                                "cpf.change@example.invalid",
                                true
                        )
                ))
        );
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities(jdbc),
                24
        );

        service.importarUsuariosDaAcademy();
        Map<String, Object> first = collaboratorCpfState(sourceId);
        service.importarUsuariosDaAcademy();
        Map<String, Object> second = collaboratorCpfState(sourceId);

        assertThat(second.get("cpf_hash"))
                .isEqualTo(CpfHasher.hashDeDigitos(
                        SECOND_CPF.replaceAll("\\D", "")
                ));
        assertThat(second.get("hash_origem"))
                .isEqualTo(first.get("hash_origem"));
        assertThat(((Number) second.get("versao_linha")).longValue())
                .isEqualTo(
                        ((Number) first.get("versao_linha")).longValue() + 1
                );
    }

    @Test
    void validToInvalidCpfRevokesLoginAndClearsLegacyLookupAtomically() {
        int sourceId = 920_702;
        String identityEmail = "cpf.revoked@example.invalid";
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                sourceId,
                                FIRST_CPF,
                                identityEmail,
                                true
                        )
                )),
                AcademyUserSnapshot.complete(List.of(
                        academyUser(
                                sourceId,
                                "000.000.000-00",
                                identityEmail,
                                true
                        )
                ))
        );
        AuthIdentityRepository identities = identities(jdbc);
        ColaboradorImportService service = service(
                jdbc,
                academy,
                identities,
                24
        );

        service.importarUsuariosDaAcademy();
        assertThat(identities.findActiveAcademyByCpf(FIRST_CPF))
                .isPresent();
        service.importarUsuariosDaAcademy();

        assertThat(identities.findActiveAcademyByCpf(FIRST_CPF))
                .isEmpty();
        Map<String, Object> collaborator = jdbc.queryForMap("""
                SELECT cpf_hash, cpf_mascarado
                FROM colaborador
                WHERE pk_origem = ?
                """, String.valueOf(sourceId));
        assertThat(collaborator)
                .containsEntry("cpf_hash", null)
                .containsEntry("cpf_mascarado", null);
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT
                    cpf_lookup_hmac,
                    cpf_lookup_key_id,
                    email_autenticacao,
                    status,
                    versao_linha
                FROM auth_identity
                WHERE colaborador_id = ?
                """,
                AcademyCollaboratorIdentity.fromAcademyUserId(sourceId)
        );
        assertThat(identity)
                .containsEntry("cpf_lookup_hmac", null)
                .containsEntry("cpf_lookup_key_id", null)
                .containsEntry("email_autenticacao", null)
                .containsEntry("status", "PENDENTE");

        service.importarUsuariosDaAcademy();
        assertThat(jdbc.queryForObject("""
                SELECT versao_linha
                FROM auth_identity
                WHERE colaborador_id = ?
                """,
                Long.class,
                AcademyCollaboratorIdentity.fromAcademyUserId(sourceId)
        )).isEqualTo(
                ((Number) identity.get("versao_linha")).longValue()
        );
    }

    @Test
    void provisioningAndAcademyImportSerializeWithoutDeadlock() throws Exception {
        int sourceId = 920_703;
        String academyEmail = "academy.serialized@example.invalid";
        String manualEmail = "manual.serialized@example.invalid";
        seedCollaborator(sourceId, LocalDateTime.now());
        AuthIdentityRepository identities = identities(jdbc);
        upsertAcademyIdentity(
                identities,
                AcademyCollaboratorIdentity.fromAcademyUserId(sourceId),
                FIRST_CPF,
                academyEmail
        );

        CountDownLatch provisioningWritten = new CountDownLatch(1);
        CountDownLatch releaseProvisioning = new CountDownLatch(1);
        CountDownLatch sourceRead = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> provisioning = executor.submit(() -> {
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource)
            );
            transaction.execute(status -> {
                identities.upsertProvisionedIdentity(
                        FIRST_CPF,
                        manualEmail,
                        AuthIdentityRepository.MANUAL_PENDING_SOURCE
                );
                provisioningWritten.countDown();
                try {
                    if (!releaseProvisioning.await(
                            10,
                            TimeUnit.SECONDS
                    )) {
                        throw new IllegalStateException(
                                "fixture provisioning was not released"
                        );
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            });
        });

        try {
            assertThat(provisioningWritten.await(
                    10,
                    TimeUnit.SECONDS
            )).isTrue();
            AcademySourceAdapter academy = mock(
                    AcademySourceAdapter.class
            );
            when(academy.fetchCompleteSnapshot(anyInt())).thenAnswer(
                    ignored -> {
                        sourceRead.countDown();
                        return AcademyUserSnapshot.complete(List.of(
                                academyUser(
                                        sourceId,
                                        FIRST_CPF,
                                        academyEmail,
                                        true
                                )
                        ));
                    }
            );
            ColaboradorImportService service = service(
                    jdbc,
                    academy,
                    identities(jdbc),
                    24
            );
            Future<ColaboradorImportResult> imported = executor.submit(
                    service::importarUsuariosDaAcademy
            );
            assertThat(sourceRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() ->
                    imported.get(250, TimeUnit.MILLISECONDS)
            ).isInstanceOf(TimeoutException.class);

            releaseProvisioning.countDown();
            provisioning.get(10, TimeUnit.SECONDS);
            assertThat(imported.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo("SUCCESS");
        } finally {
            releaseProvisioning.countDown();
            executor.shutdownNow();
        }

        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT email_autenticacao, email_fonte, status
                FROM auth_identity
                WHERE colaborador_id = ?
                """,
                AcademyCollaboratorIdentity.fromAcademyUserId(sourceId)
        );
        assertThat(identity)
                .containsEntry("email_autenticacao", manualEmail)
                .containsEntry(
                        "email_fonte",
                        AuthIdentityRepository.MANUAL_PENDING_SOURCE
                )
                .containsEntry("status", "ATIVA");
    }

    private ColaboradorImportService service(
            JdbcTemplate serviceJdbc,
            AcademySourceAdapter academy,
            AuthIdentityRepository identities,
            long graceHours
    ) {
        return service(
                serviceJdbc,
                academy,
                identities,
                mock(CortexOperationalMemoryService.class),
                graceHours
        );
    }

    private ColaboradorImportService service(
            JdbcTemplate serviceJdbc,
            AcademySourceAdapter academy,
            AuthIdentityRepository identities,
            CortexOperationalMemoryService memory,
            long graceHours
    ) {
        return new ColaboradorImportService(
                serviceJdbc,
                academy,
                memory,
                identities,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                ),
                graceHours
        );
    }

    private AuthIdentityRepository identities(JdbcTemplate identityJdbc) {
        return new AuthIdentityRepository(
                identityJdbc,
                new HmacCpfLookupDigestService(
                        "academy-atomicity-test",
                        null,
                        "test-only-academy-atomicity-hmac-material-0001",
                        null
                )
        );
    }

    private void upsertAcademyIdentity(
            AuthIdentityRepository identities,
            String collaboratorId,
            String cpf,
            String email
    ) {
        new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        ).executeWithoutResult(status ->
                identities.upsertAcademyIdentity(
                        collaboratorId,
                        cpf,
                        email
                )
        );
    }

    private HmacCpfLookupDigestService rotatingIdentityDigests() {
        return new HmacCpfLookupDigestService(
                "academy-atomicity-current",
                null,
                "test-only-academy-atomicity-current-hmac-0001",
                new HmacCpfLookupDigestService.PreviousKey(
                        "academy-atomicity-previous",
                        null,
                        "test-only-academy-atomicity-previous-hmac-0001"
                )
        );
    }

    private CortexOperationalMemoryService realMemory(
            JdbcTemplate memoryJdbc
    ) {
        return new CortexOperationalMemoryService(
                memoryJdbc,
                new ObjectMapper().findAndRegisterModules(),
                mock(ApplicationEventPublisher.class)
        );
    }

    private void seedCollaborator(
            int sourceId,
            LocalDateTime lastSeenAt
    ) {
        jdbc.update("""
                INSERT INTO colaborador (
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    codigo_colaborador,
                    nome,
                    papel_acesso,
                    ativo,
                    visto_por_ultimo_em
                )
                VALUES (?, 'dbstavias_acad', 'usuarios', ?, ?, ?,
                        'BETA', TRUE, ?)
                """,
                AcademyCollaboratorIdentity.fromAcademyUserId(sourceId),
                String.valueOf(sourceId),
                String.valueOf(sourceId),
                "Colaborador Academy Sintetico",
                lastSeenAt
        );
    }

    private void assertCollaboratorState(
            int sourceId,
            boolean active,
            boolean deleted
    ) {
        Map<String, Object> state = jdbc.queryForMap("""
                SELECT ativo, deletado_em
                FROM colaborador
                WHERE banco_origem = 'dbstavias_acad'
                  AND tabela_origem = 'usuarios'
                  AND pk_origem = ?
                """, String.valueOf(sourceId));
        assertThat(state.get("ativo")).isEqualTo(active);
        assertThat(state.get("deletado_em") != null).isEqualTo(deleted);
    }

    private Map<String, Object> collaboratorCpfState(int sourceId) {
        return jdbc.queryForMap("""
                SELECT cpf_hash, hash_origem, versao_linha
                FROM colaborador
                WHERE banco_origem = 'dbstavias_acad'
                  AND tabela_origem = 'usuarios'
                  AND pk_origem = ?
                """, String.valueOf(sourceId));
    }

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(
            int sourceId,
            String cpf,
            String email,
            boolean active
    ) {
        return academyUser(
                sourceId,
                cpf,
                email,
                active,
                "Colaborador Academy Sintetico"
        );
    }

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(
            int sourceId,
            String cpf,
            String email,
            boolean active,
            String name
    ) {
        return new AcademySourceAdapter.UsuarioAcademyRecord(
                sourceId,
                cpf,
                name,
                email,
                active,
                "grupo-teste",
                "Operacional",
                "perfil-teste",
                "Operacional",
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final List<String> domainWriteSql = new ArrayList<>();

        private RecordingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("INTO colaborador")
                    || sql.contains("UPDATE colaborador")) {
                domainWriteSql.add(sql);
            }
            return super.update(sql, args);
        }

        private List<String> domainWriteSql() {
            return List.copyOf(domainWriteSql);
        }
    }

    private static final class CheckpointFailingJdbcTemplate
            extends JdbcTemplate {

        private CheckpointFailingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO source_sync_checkpoint")) {
                throw new DataAccessResourceFailureException(
                        "driver detail: " + FIRST_CPF
                                + " first.rollback@example.invalid"
                );
            }
            return super.update(sql, args);
        }
    }
}

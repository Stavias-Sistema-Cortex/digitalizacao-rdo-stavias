package com.projeto.cortex.colaboradores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.integracoes.AcademyUserSnapshot;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
        ColaboradorImportService service = service(
                jdbc,
                academy,
                mock(AuthIdentityRepository.class),
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

    private ColaboradorImportService service(
            JdbcTemplate serviceJdbc,
            AcademySourceAdapter academy,
            AuthIdentityRepository identities,
            long graceHours
    ) {
        return new ColaboradorImportService(
                serviceJdbc,
                academy,
                mock(CortexOperationalMemoryService.class),
                identities,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                ),
                graceHours
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

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(
            int sourceId,
            String cpf,
            String email,
            boolean active
    ) {
        return new AcademySourceAdapter.UsuarioAcademyRecord(
                sourceId,
                cpf,
                "Colaborador Academy Sintetico",
                email,
                active,
                "grupo-teste",
                "Operacional",
                "perfil-teste",
                "Operacional",
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
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

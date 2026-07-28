package com.projeto.cortex.obras;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlObraLifecycleIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("obra_lifecycle_it");

    private static JdbcTemplate jdbc;
    private static String obraComEstadoId;
    private static String obraSemEstadoId;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target(MigrationVersion.fromVersion("62"))
                .load()
                .migrate();

        var dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        obraComEstadoId = UUID.randomUUID().toString();
        obraSemEstadoId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, fonte_criacao, versao_linha
                ) VALUES (?, ?, ?, 'ATIVA', 'MANUAL', 0)
                """,
                obraComEstadoId,
                "LIFECYCLE-CANONICAL",
                "Obra com estado canônico"
        );
        jdbc.update(
                """
                INSERT INTO cortex_estado_entidade (
                    tipo_entidade, entidade_id, versao_entidade
                ) VALUES ('OBRA', ?, 7)
                """,
                obraComEstadoId
        );
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, fonte_criacao, versao_linha
                ) VALUES (?, ?, ?, 'INATIVA', 'MANUAL', 3)
                """,
                obraSemEstadoId,
                "LIFECYCLE-LOCAL",
                "Obra sem estado canônico"
        );

        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
    }

    @Test
    void alignsExistingWorksiteVersionToCanonicalStateWhenPresent() {
        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM obra WHERE id = ?",
                Long.class,
                obraComEstadoId
        )).isEqualTo(7L);
        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM obra WHERE id = ?",
                Long.class,
                obraSemEstadoId
        )).isEqualTo(3L);
    }

    @Test
    void defaultsNewWorksitesToVersionOne() {
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, fonte_criacao
                ) VALUES (?, ?, ?, 'ATIVA', 'MANUAL')
                """,
                obraId,
                "LIFECYCLE-NEW",
                "Obra nova"
        );

        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM obra WHERE id = ?",
                Long.class,
                obraId
        )).isEqualTo(1L);
    }

    @Test
    void createsArchivedWorksitePartialIndexWithoutRestrictingStatus() {
        String definition = jdbc.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'idx_obra_arquivadas_atualizado'
                """,
                String.class
        );
        Integer statusChecks = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'obra'::regclass
                  AND contype = 'c'
                  AND pg_get_constraintdef(oid) ILIKE '%status%'
                """,
                Integer.class
        );

        assertThat(definition)
                .contains("atualizado_em")
                .contains("arquivado_em IS NOT NULL");
        assertThat(statusChecks).isZero();
    }
}

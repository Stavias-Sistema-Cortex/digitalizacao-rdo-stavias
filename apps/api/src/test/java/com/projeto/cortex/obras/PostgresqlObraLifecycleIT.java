package com.projeto.cortex.obras;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostgresqlObraLifecycleIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("obra_lifecycle_it");

    private static JdbcTemplate jdbc;
    private static String obraComEstadoId;
    private static String obraSemEstadoId;
    private static final LocalDateTime ATUALIZADO_EM_ORIGINAL =
            LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_000_000);

    @Autowired
    private ObraRepository repository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("spring.datasource.driver-class-name", () ->
                "org.postgresql.Driver"
        );
    }

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
                    id, codigo_contrato, nome, status, fonte_criacao,
                    versao_linha, atualizado_em
                ) VALUES (?, ?, ?, 'ATIVA', 'MANUAL', 0, ?)
                """,
                obraComEstadoId,
                "LIFECYCLE-CANONICAL",
                "Obra com estado canônico",
                ATUALIZADO_EM_ORIGINAL
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
    void preservesOperationalTimestampAndReenablesTouchTriggerAfterBackfill() {
        assertThat(jdbc.queryForObject(
                "SELECT atualizado_em FROM obra WHERE id = ?",
                LocalDateTime.class,
                obraComEstadoId
        )).isEqualTo(ATUALIZADO_EM_ORIGINAL);
        assertThat(jdbc.queryForObject(
                """
                SELECT tgenabled
                FROM pg_trigger
                WHERE tgrelid = 'obra'::regclass
                  AND tgname = 'trg_obra_touch_atualizado_em'
                """,
                String.class
        )).isEqualTo("O");
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

    @Test
    void repositorySeparatesActiveAndArchivedListingsSearchAndOrdering() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String activeId = insertWorksite(
                "ACTIVE-" + suffix,
                "Ponte ativa " + suffix,
                LocalDateTime.of(2025, 1, 3, 10, 0),
                null
        );
        String archivedOlderId = insertWorksite(
                "ARCHIVED-OLDER-" + suffix,
                "Ponte arquivada " + suffix,
                LocalDateTime.of(2025, 1, 1, 10, 0),
                LocalDateTime.of(2025, 2, 1, 10, 0)
        );
        String archivedNewerId = insertWorksite(
                "ARCHIVED-NEWER-" + suffix,
                "Viaduto arquivado " + suffix,
                LocalDateTime.of(2025, 1, 2, 10, 0),
                LocalDateTime.of(2025, 2, 2, 10, 0)
        );

        List<String> activeIds = repository.listar(PageRequest.of(0, 100))
                .stream()
                .map(Obra::getId)
                .toList();
        List<String> archivedIds = repository
                .listarArquivadas(PageRequest.of(0, 100))
                .stream()
                .map(Obra::getId)
                .toList();
        List<String> archivedSearchIds = repository.buscarArquivadas(
                        "Ponte arquivada " + suffix,
                        PageRequest.of(0, 100)
                )
                .stream()
                .map(Obra::getId)
                .toList();
        List<String> activeSearchIds = repository.buscar(
                        "Ponte ativa " + suffix,
                        PageRequest.of(0, 100)
                )
                .stream()
                .map(Obra::getId)
                .toList();

        assertThat(activeIds)
                .contains(activeId)
                .doesNotContain(archivedOlderId, archivedNewerId);
        assertThat(archivedIds)
                .contains(archivedOlderId, archivedNewerId)
                .doesNotContain(activeId);
        assertThat(archivedIds.indexOf(archivedNewerId))
                .isLessThan(archivedIds.indexOf(archivedOlderId));
        assertThat(archivedSearchIds).containsExactly(archivedOlderId);
        assertThat(activeSearchIds)
                .contains(activeId)
                .doesNotContain(archivedOlderId, archivedNewerId);
    }

    private String insertWorksite(
            String code,
            String name,
            LocalDateTime updatedAt,
            LocalDateTime archivedAt
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, fonte_criacao,
                    atualizado_em, arquivado_em
                ) VALUES (?, ?, ?, 'ATIVA', 'MANUAL', ?, ?)
                """,
                id,
                code,
                name,
                updatedAt,
                archivedAt
        );
        return id;
    }
}

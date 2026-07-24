package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlV55RdoContextReceiptIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_v55_rdo_context_it");

    @Test
    void preservaReceiptsLegadosDuplicadosEProtegeNovosReceiptsCanonicos() {
        migrateTo("54");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        ));
        Integer v54Checksum = jdbc.queryForObject(
                """
                SELECT checksum
                FROM flyway_schema_history
                WHERE success = TRUE AND version = '54'
                """,
                Integer.class
        );
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId, "CTR-" + obraId, "Obra receipt V55"
        );
        inserirSnapshot(jdbc, obraId, null);
        inserirSnapshot(jdbc, obraId, null);

        migrateTo("55");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_creation_context_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_creation_context_snapshot
                WHERE obra_id = ? AND canonical_key IS NULL
                """,
                Integer.class,
                obraId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT checksum
                FROM flyway_schema_history
                WHERE success = TRUE AND version = '54'
                """,
                Integer.class
        )).isEqualTo(v54Checksum);

        String canonicalKey = "a".repeat(64);
        inserirSnapshot(jdbc, obraId, canonicalKey);
        assertThatThrownBy(() -> inserirSnapshot(jdbc, obraId, canonicalKey))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void inserirSnapshot(
            JdbcTemplate jdbc,
            String obraId,
            String canonicalKey
    ) {
        if (canonicalKey == null) {
            jdbc.update(
                    """
                    INSERT INTO rdo_creation_context_snapshot (
                        snapshot_id, obra_id, selected_date, previous_rdo_id,
                        source_version, payload_hash, coverage_json,
                        generated_at, stale_after
                    ) VALUES (?, ?, ?, NULL, 101, ?, '{}'::jsonb, now(), now() + interval '15 minutes')
                    """,
                    UUID.randomUUID().toString(),
                    obraId,
                    LocalDate.of(2026, 7, 22),
                    "b".repeat(64)
            );
            return;
        }
        jdbc.update(
                """
                INSERT INTO rdo_creation_context_snapshot (
                    snapshot_id, obra_id, selected_date, previous_rdo_id,
                    source_version, payload_hash, coverage_json,
                    generated_at, stale_after, canonical_key
                ) VALUES (?, ?, ?, NULL, 101, ?, '{}'::jsonb, now(), now() + interval '15 minutes', ?)
                """,
                UUID.randomUUID().toString(),
                obraId,
                LocalDate.of(2026, 7, 22),
                "b".repeat(64),
                canonicalKey
        );
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target(version)
                .load()
                .migrate();
    }
}

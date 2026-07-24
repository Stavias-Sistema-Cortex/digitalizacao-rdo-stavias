package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
class PostgresqlV57RdoContextProvenanceIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_v57_rdo_provenance_it");

    @Test
    void upgradePreservaOrfaoLegadoEProtegeTodaNovaProvenienciaRdo() {
        migrateTo("56");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
        PostgresqlSchemaReadinessGuard readiness =
                new PostgresqlSchemaReadinessGuard(jdbc, "57");
        assertThatThrownBy(readiness::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V57");
        Integer v56Checksum = jdbc.queryForObject(
                """
                SELECT checksum
                FROM flyway_schema_history
                WHERE success = TRUE AND version = '56'
                """,
                Integer.class
        );
        String obraId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "CTR-" + obraId,
                "Obra upgrade V57"
        );
        jdbc.update(
                """
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo,
                    creation_context_version
                ) VALUES (?, ?, 'RDO-LEGADO', ?, 999999)
                """,
                id(),
                obraId,
                LocalDate.of(2026, 7, 20)
        );

        migrateTo("57");

        assertThatCode(readiness::verifyReadiness).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_constraint
                WHERE conrelid = 'rdo'::regclass
                  AND conname = 'fk_rdo_creation_context_receipt'
                  AND contype = 'f'
                  AND confdeltype = 'r'
                  AND convalidated = FALSE
                """,
                Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'rdo'
                  AND indexname = 'idx_rdo_creation_context_version'
                """,
                Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'rdo_creation_context_snapshot'
                  AND column_name = 'issued_by_id'
                """,
                Integer.class
        )).isOne();

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo,
                    creation_context_version
                ) VALUES (?, ?, 'RDO-FORJADO', ?, 888888)
                """,
                id(),
                obraId,
                LocalDate.of(2026, 7, 21)
        )).isInstanceOf(DataIntegrityViolationException.class);

        Long receiptVersion = jdbc.queryForObject(
                """
                INSERT INTO rdo_creation_context_snapshot (
                    snapshot_id, obra_id, selected_date, previous_rdo_id,
                    source_version, payload_hash, coverage_json,
                    generated_at, stale_after, canonical_key, issued_by_id
                ) VALUES (
                    ?, ?, ?, NULL, 101, ?, '{}'::jsonb,
                    now(), now() + interval '15 minutes', ?, ?
                )
                RETURNING receipt_version
                """,
                Long.class,
                id(),
                obraId,
                LocalDate.of(2026, 7, 22),
                "b".repeat(64),
                "a".repeat(64),
                id()
        );
        String validRdoId = id();
        jdbc.update(
                """
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo,
                    creation_context_version
                ) VALUES (?, ?, 'RDO-VALIDO', ?, ?)
                """,
                validRdoId,
                obraId,
                LocalDate.of(2026, 7, 22),
                receiptVersion
        );

        assertThatThrownBy(() -> jdbc.update(
                """
                DELETE FROM rdo_creation_context_snapshot
                WHERE receipt_version = ?
                """,
                receiptVersion
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo
                WHERE id = ? AND creation_context_version = ?
                """,
                Integer.class,
                validRdoId,
                receiptVersion
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT checksum
                FROM flyway_schema_history
                WHERE success = TRUE AND version = '56'
                """,
                Integer.class
        )).isEqualTo(v56Checksum);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE success = TRUE AND version IN ('56', '57')
                """,
                Integer.class
        )).isEqualTo(2);
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

    private static String id() {
        return UUID.randomUUID().toString();
    }
}

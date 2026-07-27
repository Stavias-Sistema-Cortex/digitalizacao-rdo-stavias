package com.projeto.cortex.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlV54ReadinessIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_v54_readiness_it");

    @Test
    void upgradesImmutableV53AndRequiresRevenueOnlyPdorMetadata() {
        migrateTo("53");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        ));
        Integer v53Checksum = jdbc.queryForObject("""
                SELECT checksum FROM flyway_schema_history
                WHERE success = TRUE AND version = '53'
                """, Integer.class);
        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbc, "54"
        );

        assertThatThrownBy(guard::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V54");

        migrateTo("54");

        assertThatCode(guard::verifyReadiness).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE success = TRUE AND version = '54'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT checksum FROM flyway_schema_history
                WHERE success = TRUE AND version = '53'
                """, Integer.class)).isEqualTo(v53Checksum);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'pdor_snapshot'
                  AND column_name IN (
                      'algorithm_version', 'evidence_ids_json',
                      'evidence_high_water_mark', 'coverage_code',
                      'assumptions_json', 'executed_at_utc',
                      'is_stale', 'is_current'
                  )
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'pdor_calculation_failure'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'previsao_financeira_snapshot'
                  AND column_name IN (
                      'custo_realizado', 'custo_comprometido',
                      'custo_previsto_final', 'margem_atual',
                      'margem_prevista', 'margem_percentual'
                  )
                """, Integer.class)).isEqualTo(6);

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.triggers
                WHERE trigger_schema = 'public'
                  AND trigger_name IN (
                      'trg_pdor_revenue_snapshot_immutable',
                      'trg_pdor_calculation_failure_immutable'
                  )
                """, Integer.class)).isEqualTo(4);
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target(version)
                .load()
                .migrate();
    }
}

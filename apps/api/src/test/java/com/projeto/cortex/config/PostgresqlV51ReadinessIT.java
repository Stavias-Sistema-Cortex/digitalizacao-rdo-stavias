package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlV51ReadinessIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_v51_readiness_it");

    @Test
    void upgradesOriginalV49AndV50ToV51WithCommittedBaseline() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target("50")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
        String obra = "00000000-0000-4000-8000-000000005101";
        String actor = "00000000-0000-4000-8000-000000005102";
        String service = "00000000-0000-4000-8000-000000005103";
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, 'V51', 'V51')",
                obra
        );
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, 'V51', 'ALFA')
                """, actor, actor);
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, 'V51.BASELINE', 'V51 baseline', 'ACTIVE', ?, ?)
                """, service, obra, actor);

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbc, "51"
        );
        assertThatThrownBy(guard::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V51");

        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target("51")
                .load()
                .migrate();

        assertThatCode(guard::verifyReadiness).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'service_catalog_revision'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = 'commit_revision'
                  AND table_name IN (
                      'catalogo_servico',
                      'service_catalog_mutation',
                      'service_price_version',
                      'service_price_version_cancellation'
                  )
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT commit_revision FROM catalogo_servico WHERE id = ?",
                Long.class,
                service
        )).isZero();
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, 'V51.NEW', 'V51 new', 'ACTIVE', ?, ?)
                """, "00000000-0000-4000-8000-000000005104", obra, actor);
        assertThat(jdbc.queryForObject(
                "SELECT revision FROM service_catalog_revision WHERE singleton = TRUE",
                Long.class
        )).isOne();
    }
}

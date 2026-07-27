package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlV52ReadinessIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_v52_readiness_it");

    @Test
    void upgradesImmutableV51AndBackfillsOnlyProvableRevenueEvidence() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target("51")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        ));
        String obra = "00000000-0000-4000-8000-000000005201";
        String actor = "00000000-0000-4000-8000-000000005202";
        String rdo = "00000000-0000-4000-8000-000000005203";
        String service = "00000000-0000-4000-8000-000000005204";
        String price = "00000000-0000-4000-8000-000000005205";
        String item = "00000000-0000-4000-8000-000000005206";
        String unmatchedItem = "00000000-0000-4000-8000-000000005207";
        String exactExecution = "00000000-0000-4000-8000-000000005208";
        String unpricedExecution = "00000000-0000-4000-8000-000000005209";
        LocalDate executionDate = LocalDate.of(2026, 7, 22);

        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, 'V52', 'V52')",
                obra
        );
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, 'V52', 'ALFA')
                """, actor, actor);
        jdbc.update(
                "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, 'RDO-V52', ?)",
                rdo, obra, executionDate
        );
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, 'V52.SERVICE', 'V52 service', 'ACTIVE', ?, ?)
                """, service, obra, actor);
        jdbc.update("""
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, fonte, criado_por
                ) VALUES (?, ?, ?, 'M2', 'BRL', 1, 10.0000, ?, 'V52_TEST', ?)
                """, price, obra, service, executionDate.minusDays(1), actor);
        insertContractItem(jdbc, item, obra, "V52.SERVICE");
        insertContractItem(jdbc, unmatchedItem, obra, "V52.UNMATCHED");
        insertLegacyExecution(
                jdbc, exactExecution, rdo, obra, item,
                executionDate, "VALIDADA", "3.000", "a"
        );
        insertLegacyExecution(
                jdbc, unpricedExecution, rdo, obra, unmatchedItem,
                executionDate, "VALIDADA", "4.000", "b"
        );

        PostgresqlSchemaReadinessGuard guard = new PostgresqlSchemaReadinessGuard(
                jdbc, "52"
        );
        assertThatThrownBy(guard::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V52");

        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target("52")
                .load()
                .migrate();

        assertThatCode(guard::verifyReadiness).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'execucao_servico_rdo'
                  AND column_name IN (
                      'service_id', 'price_version_id', 'unit_price_snapshot',
                      'revenue_amount', 'revenue_coverage_code',
                      'revenue_evidence_id', 'revenue_event_id', 'accepted_at'
                  )
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT service_id FROM execucao_servico_rdo WHERE id = ?",
                String.class, exactExecution
        )).isEqualTo(service);
        assertThat(jdbc.queryForObject(
                "SELECT price_version_id FROM execucao_servico_rdo WHERE id = ?",
                String.class, exactExecution
        )).isEqualTo(price);
        assertThat(jdbc.queryForObject(
                "SELECT revenue_amount FROM execucao_servico_rdo WHERE id = ?",
                BigDecimal.class, exactExecution
        )).isEqualByComparingTo("30.00");
        assertThat(jdbc.queryForObject(
                "SELECT revenue_coverage_code FROM execucao_servico_rdo WHERE id = ?",
                String.class, exactExecution
        )).isEqualTo("ACCEPTED_EXACT");
        assertThat(jdbc.queryForObject(
                "SELECT revenue_coverage_code FROM execucao_servico_rdo WHERE id = ?",
                String.class, unpricedExecution
        )).isEqualTo("HISTORICAL_UNPRICED");
        assertThat(jdbc.queryForObject(
                "SELECT revenue_evidence_id FROM execucao_servico_rdo WHERE id = ?",
                String.class, unpricedExecution
        )).isNull();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE execucao_servico_rdo SET quantidade_executada = 9 WHERE id = ?",
                exactExecution
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_EVIDENCE_IMMUTABLE");
    }

    private static void insertContractItem(
            JdbcTemplate jdbc,
            String id,
            String obra,
            String code
    ) {
        jdbc.update("""
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total, status
                ) VALUES (?, ?, 'V52', ?, ?, 'M2', 100, 10, 1000, 'ATIVO')
                """, id, obra, code, code);
    }

    private static void insertLegacyExecution(
            JdbcTemplate jdbc,
            String id,
            String rdo,
            String obra,
            String item,
            LocalDate date,
            String status,
            String quantity,
            String keyCharacter
    ) {
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, item_contratual_id,
                    quantidade_executada, unidade_medida, data_execucao,
                    status_validacao, estado_receita, fonte, chave_execucao
                ) VALUES (?, ?, ?, 'Legacy service', ?, ?, 'M2', ?, ?,
                          'PRODUCAO_VALIDADA', 'RDO', ?)
                """, id, rdo, obra, item, new BigDecimal(quantity), date, status,
                keyCharacter.repeat(64));
    }
}

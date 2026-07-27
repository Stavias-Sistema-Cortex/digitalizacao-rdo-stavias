package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.financeiro.RastreioReceitaEvidenceResponse;
import com.projeto.cortex.financeiro.RastreioReceitaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlV59RevenueOntologyBackfillIT {

    private static final LocalDate EXECUTION_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private final PostgreSQLContainer<?> database =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_v59_revenue_ontology_it");

    @Test
    void upgradesV52HistoricalEvidenceIntoPersistedCanonicalOntologyChain() {
        migrateTo("51");
        JdbcTemplate jdbc = jdbc();
        Fixture fixture = seedV51HistoricalRevenue(jdbc);

        migrateTo("58");

        RastreioReceitaService trace = new RastreioReceitaService(jdbc);
        assertThatThrownBy(() -> trace.evidencia(
                Set.of(fixture.obraId()), fixture.exactExecutionId()
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "REVENUE_EVIDENCE_NOT_FOUND_OR_FORBIDDEN"
                );
        assertThat(graphObjectCount(jdbc, fixture)).isZero();
        assertThat(relationCount(jdbc, fixture.exactExecutionId())).isZero();
        Integer v58Checksum = checksum(jdbc, "58");

        migrateTo("59");

        assertThat(checksum(jdbc, "58")).isEqualTo(v58Checksum);
        assertThat(checksum(jdbc, "59")).isNotNull();
        RastreioReceitaEvidenceResponse evidence = trace.evidencia(
                Set.of(fixture.obraId()), fixture.exactExecutionId()
        );
        assertThat(evidence.row().executionId())
                .isEqualTo(fixture.exactExecutionId());
        assertThat(evidence.row().revenue()).isEqualByComparingTo("30.00");
        assertThat(evidence.ontologyLinks())
                .extracting(
                        RastreioReceitaEvidenceResponse.OntologyLink::relationType
                )
                .containsExactly(
                        "EXECUTED_IN",
                        "EXECUTES_SERVICE",
                        "GENERATES_REVENUE",
                        "PRICED_BY"
                );
        assertThat(evidence.ontologyLinks()).allSatisfy(link -> {
            assertThat(link.sourceType())
                    .isEqualTo("RDO_SERVICE_EXECUTED");
            assertThat(link.sourceId())
                    .isEqualTo(fixture.exactExecutionId());
            assertThat(link.active()).isTrue();
        });

        assertThat(graphObjectCount(jdbc, fixture)).isEqualTo(5);
        assertThat(jdbc.queryForMap(
                """
                SELECT tabela_entidade, status, fonte,
                       metadados_json ->> 'rdoId' AS rdo_id,
                       metadados_json ->> 'obraId' AS obra_id
                FROM cortex_objeto
                WHERE tipo_entidade = 'RDO_SERVICE_EXECUTED'
                  AND entidade_id = ?
                """,
                fixture.exactExecutionId()
        ))
                .containsEntry("tabela_entidade", "execucao_servico_rdo")
                .containsEntry("status", "ACCEPTED")
                .containsEntry("fonte", "CORTEX_FINANCEIRO")
                .containsEntry("rdo_id", fixture.rdoId())
                .containsEntry("obra_id", fixture.obraId());
        assertThat(jdbc.queryForMap(
                """
                SELECT tabela_entidade, status, fonte,
                       metadados_json ->> 'executionId' AS execution_id,
                       metadados_json ->> 'rdoId' AS rdo_id,
                       metadados_json ->> 'obraId' AS obra_id
                FROM cortex_objeto
                WHERE tipo_entidade = 'REVENUE_EVIDENCE'
                  AND entidade_id = (
                      SELECT revenue_evidence_id
                      FROM execucao_servico_rdo
                      WHERE id = ?
                  )
                """,
                fixture.exactExecutionId()
        ))
                .containsEntry("tabela_entidade", "execucao_servico_rdo")
                .containsEntry("status", "ACCEPTED")
                .containsEntry("fonte", "CORTEX_FINANCEIRO")
                .containsEntry("execution_id", fixture.exactExecutionId())
                .containsEntry("rdo_id", fixture.rdoId())
                .containsEntry("obra_id", fixture.obraId());
        assertThat(relationCount(jdbc, fixture.exactExecutionId())).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_relacao
                WHERE origem_tipo = 'RDO_SERVICE_EXECUTED'
                  AND origem_id = ?
                  AND ativa = TRUE
                  AND fonte = 'CORTEX_FINANCEIRO'
                  AND tipo_relacao IN (
                      'EXECUTED_IN',
                      'EXECUTES_SERVICE',
                      'GENERATES_REVENUE',
                      'PRICED_BY'
                  )
                """,
                Integer.class,
                fixture.exactExecutionId()
        )).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_objeto
                WHERE entidade_id = ?
                   OR entidade_id = (
                       SELECT revenue_evidence_id
                       FROM execucao_servico_rdo
                       WHERE id = ?
                   )
                """,
                Integer.class,
                fixture.unpricedExecutionId(),
                fixture.unpricedExecutionId()
        )).isZero();

        migrateTo("59");

        assertThat(graphObjectCount(jdbc, fixture)).isEqualTo(5);
        assertThat(relationCount(jdbc, fixture.exactExecutionId())).isEqualTo(4);
    }

    private Fixture seedV51HistoricalRevenue(JdbcTemplate jdbc) {
        String obraId = id("5901");
        String actorId = id("5902");
        String rdoId = id("5903");
        String serviceId = id("5904");
        String priceId = id("5905");
        String itemId = id("5906");
        String unmatchedItemId = id("5907");
        String exactExecutionId = id("5908");
        String unpricedExecutionId = id("5909");

        jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, nome)
                VALUES (?, 'V59', 'Obra histórica V59')
                """,
                obraId
        );
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?,
                          'Actor V59', 'ALFA')
                """,
                actorId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo)
                VALUES (?, ?, 'RDO-V59', ?)
                """,
                rdoId,
                obraId,
                EXECUTION_DATE
        );
        jdbc.update(
                """
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, 'V59.SERVICE', 'Serviço histórico V59',
                          'ACTIVE', ?, ?)
                """,
                serviceId,
                obraId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, fonte, criado_por
                ) VALUES (?, ?, ?, 'M2', 'BRL', 1, 10.0000, ?,
                          'V59_TEST', ?)
                """,
                priceId,
                obraId,
                serviceId,
                EXECUTION_DATE.minusDays(1),
                actorId
        );
        insertContractItem(jdbc, itemId, obraId, "V59.SERVICE");
        insertContractItem(jdbc, unmatchedItemId, obraId, "V59.UNMATCHED");
        insertLegacyExecution(
                jdbc,
                exactExecutionId,
                rdoId,
                obraId,
                itemId,
                "3.000",
                "a"
        );
        insertLegacyExecution(
                jdbc,
                unpricedExecutionId,
                rdoId,
                obraId,
                unmatchedItemId,
                "4.000",
                "b"
        );
        return new Fixture(
                obraId,
                rdoId,
                serviceId,
                priceId,
                exactExecutionId,
                unpricedExecutionId
        );
    }

    private void insertContractItem(
            JdbcTemplate jdbc,
            String itemId,
            String obraId,
            String code
    ) {
        jdbc.update(
                """
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total, status
                ) VALUES (?, ?, 'V59', ?, ?, 'M2', 100, 10, 1000, 'ATIVO')
                """,
                itemId,
                obraId,
                code,
                code
        );
    }

    private void insertLegacyExecution(
            JdbcTemplate jdbc,
            String executionId,
            String rdoId,
            String obraId,
            String itemId,
            String quantity,
            String keyCharacter
    ) {
        jdbc.update(
                """
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, item_contratual_id,
                    quantidade_executada, unidade_medida, data_execucao,
                    status_validacao, estado_receita, fonte, chave_execucao
                ) VALUES (?, ?, ?, 'Legacy service', ?, ?, 'M2', ?,
                          'VALIDADA', 'PRODUCAO_VALIDADA', 'RDO', ?)
                """,
                executionId,
                rdoId,
                obraId,
                itemId,
                new BigDecimal(quantity),
                EXECUTION_DATE,
                keyCharacter.repeat(64)
        );
    }

    private int graphObjectCount(JdbcTemplate jdbc, Fixture fixture) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_objeto
                WHERE (tipo_entidade, entidade_id) IN (
                    ('RDO', ?),
                    ('SERVICE', ?),
                    ('SERVICE_PRICE_VERSION', ?),
                    ('RDO_SERVICE_EXECUTED', ?),
                    (
                        'REVENUE_EVIDENCE',
                        (
                            SELECT revenue_evidence_id
                            FROM execucao_servico_rdo
                            WHERE id = ?
                        )
                    )
                )
                """,
                Integer.class,
                fixture.rdoId(),
                fixture.serviceId(),
                fixture.priceId(),
                fixture.exactExecutionId(),
                fixture.exactExecutionId()
        );
    }

    private int relationCount(JdbcTemplate jdbc, String executionId) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_relacao
                WHERE origem_tipo = 'RDO_SERVICE_EXECUTED'
                  AND origem_id = ?
                  AND ativa = TRUE
                """,
                Integer.class,
                executionId
        );
    }

    private Integer checksum(JdbcTemplate jdbc, String version) {
        return jdbc.queryForObject(
                """
                SELECT checksum
                FROM flyway_schema_history
                WHERE success = TRUE
                  AND version = ?
                """,
                Integer.class,
                version
        );
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword()
        ));
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(
                        database.getJdbcUrl(),
                        database.getUsername(),
                        database.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target(version)
                .load()
                .migrate();
    }

    private String id(String suffix) {
        return "00000000-0000-4000-8000-00000000" + suffix;
    }

    private record Fixture(
            String obraId,
            String rdoId,
            String serviceId,
            String priceId,
            String exactExecutionId,
            String unpricedExecutionId
    ) {
    }
}

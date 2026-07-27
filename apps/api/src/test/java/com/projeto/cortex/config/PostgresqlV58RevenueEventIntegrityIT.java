package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlV58RevenueEventIntegrityIT {

    private static final LocalDate EXECUTION_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private final PostgreSQLContainer<?> database =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_v58_revenue_event_it");

    @Test
    void upgradeRequiresV58AndLinksAcceptedRevenueToCanonicalEvent() {
        migrateTo("57");
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        Integer v57Checksum = checksum(jdbc, "57");
        PostgresqlSchemaReadinessGuard readiness =
                new PostgresqlSchemaReadinessGuard(jdbc, "58");

        assertThatThrownBy(readiness::verifyReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V58");

        migrateTo("58");

        assertThatCode(readiness::verifyReadiness).doesNotThrowAnyException();
        assertThat(checksum(jdbc, "57")).isEqualTo(v57Checksum);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_constraint
                WHERE conrelid = 'execucao_servico_rdo'::regclass
                  AND conname = 'fk_execucao_revenue_event'
                  AND contype = 'f'
                  AND confdeltype = 'r'
                  AND condeferrable = TRUE
                  AND condeferred = TRUE
                  AND convalidated = TRUE
                """,
                Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_proc function
                JOIN pg_namespace namespace
                  ON namespace.oid = function.pronamespace
                WHERE namespace.nspname = 'public'
                  AND function.proname IN (
                      'cortex_revenue_event_matches_v58',
                      'cortex_validate_revenue_event_v58',
                      'cortex_guard_revenue_event_v58'
                  )
                  AND function.proconfig @>
                      ARRAY['search_path=pg_catalog, public']::text[]
                """,
                Integer.class
        )).isEqualTo(3);

        Fixture fixture = fixture(jdbc);
        String acceptedExecutionId = id();
        String canonicalEventId = id();

        assertThatCode(() -> transactions.executeWithoutResult(ignored -> {
            insertAcceptedExecution(
                    jdbc,
                    fixture,
                    acceptedExecutionId,
                    canonicalEventId
            );
            insertCanonicalEvent(
                    jdbc,
                    fixture,
                    acceptedExecutionId,
                    canonicalEventId
            );
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM execucao_servico_rdo execution
                JOIN cortex_evento_operacional event
                  ON event.id = execution.revenue_event_id
                WHERE execution.id = ?
                  AND event.tipo_evento = 'RDO_SERVICE_EXECUTED'
                """,
                Integer.class,
                acceptedExecutionId
        )).isOne();

        assertThatThrownBy(() -> insertAcceptedExecution(
                jdbc,
                fixture,
                id(),
                id()
        )).isInstanceOf(DataIntegrityViolationException.class);

        String mismatchedEventId = id();
        insertMismatchedCanonicalEvent(jdbc, fixture, mismatchedEventId);
        assertThatThrownBy(() -> insertAcceptedExecution(
                jdbc,
                fixture,
                id(),
                mismatchedEventId
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("RDO_REVENUE_EVENT_MISMATCH");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM cortex_evento_operacional WHERE id = ?",
                canonicalEventId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE cortex_evento_operacional
                SET payload_json = jsonb_set(
                    payload_json,
                    '{revenue}',
                    '"999.99"'::jsonb
                )
                WHERE id = ?
                """,
                canonicalEventId
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("RDO_REVENUE_EVENT_IMMUTABLE");
        assertImmutableEventUpdate(
                jdbc,
                canonicalEventId,
                "commit_seq = commit_seq + 1000000"
        );
        assertImmutableEventUpdate(
                jdbc,
                canonicalEventId,
                "resultado = 'FALHA'"
        );
        assertImmutableEventUpdate(
                jdbc,
                canonicalEventId,
                "ocorrido_em = ocorrido_em + interval '1 second'"
        );
    }

    @Test
    void upgradeFailsClosedWhenLegacyAcceptedRevenueReferencesMissingEvent() {
        migrateTo("57");
        JdbcTemplate jdbc = jdbc();
        Fixture fixture = fixture(jdbc);
        insertAcceptedExecution(jdbc, fixture, id(), id());

        assertThatThrownBy(() -> migrateTo("58"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V58");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE success = TRUE AND version = '58'
                """,
                Integer.class
        )).isZero();
    }

    @Test
    void upgradeFailsClosedWhenExistingEventDoesNotMatchAcceptedRevenue() {
        migrateTo("57");
        JdbcTemplate jdbc = jdbc();
        Fixture fixture = fixture(jdbc);
        String eventId = id();
        insertMismatchedCanonicalEvent(jdbc, fixture, eventId);
        insertAcceptedExecution(jdbc, fixture, id(), eventId);

        assertThatThrownBy(() -> migrateTo("58"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V58");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE success = TRUE AND version = '58'
                """,
                Integer.class
        )).isZero();
    }

    private Fixture fixture(JdbcTemplate jdbc) {
        String obraId = id();
        String actorId = id();
        String rdoId = id();
        String serviceId = id();
        String priceId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "CTR-" + obraId,
                "Obra V58"
        );
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, 'Actor V58', 'ALFA')
                """,
                actorId,
                actorId
        );
        jdbc.update(
                "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, 'RDO-V58', ?)",
                rdoId,
                obraId,
                EXECUTION_DATE
        );
        jdbc.update(
                """
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, ?, 'Serviço V58', 'ACTIVE', ?, ?)
                """,
                serviceId,
                "SERVICE.V58." + serviceId.substring(0, 8).toUpperCase(),
                obraId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, fonte, criado_por
                ) VALUES (?, ?, ?, 'M2', 'BRL', 1, 10.0000, ?, 'V58_IT', ?)
                """,
                priceId,
                obraId,
                serviceId,
                EXECUTION_DATE.minusDays(1),
                actorId
        );
        return new Fixture(obraId, rdoId, serviceId, priceId);
    }

    private void insertCanonicalEvent(
            JdbcTemplate jdbc,
            Fixture fixture,
            String executionId,
            String eventId
    ) {
        jdbc.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    entidades_relacionadas_json, estado_novo_json,
                    payload_json, ocorrido_em
                )
                SELECT ?,
                       (
                           SELECT COALESCE(MAX(commit_seq), 0) + 1
                           FROM cortex_evento_operacional
                       ),
                       'RDO_EXECUTION', execution.id, execution.obra_id,
                       execution.rdo_id,
                       'RDO_SERVICE_EXECUTED', 'CORTEX_FINANCEIRO', 'ONLINE',
                       'SYNCED', 1,
                       jsonb_build_array(
                           jsonb_build_object(
                               'tipo', 'RDO', 'id', execution.rdo_id
                           ),
                           jsonb_build_object(
                               'tipo', 'WORKSITE', 'id', execution.obra_id
                           ),
                           jsonb_build_object(
                               'tipo', 'SERVICE', 'id', execution.service_id
                           ),
                           jsonb_build_object(
                               'tipo', 'SERVICE_PRICE_VERSION',
                               'id', execution.price_version_id
                           ),
                           jsonb_build_object(
                               'tipo', 'REVENUE_EVIDENCE',
                               'id', execution.revenue_evidence_id
                           )
                       ),
                       jsonb_build_object(
                           'status', 'ACCEPTED',
                           'revenueEvidenceId', execution.revenue_evidence_id
                       ),
                       jsonb_build_object(
                           'schemaVersion', 1,
                           'rdoId', execution.rdo_id,
                           'obraId', execution.obra_id,
                           'serviceId', execution.service_id,
                           'priceVersionId', execution.price_version_id,
                           'revenueEvidenceId', execution.revenue_evidence_id,
                           'acceptedQuantity', execution.quantidade_executada,
                           'unit', execution.unidade_medida,
                           'unitPrice', execution.unit_price_snapshot,
                           'currency', execution.currency,
                           'revenue', execution.revenue_amount,
                           'status', 'ACCEPTED'
                       ),
                       execution.accepted_at
                FROM execucao_servico_rdo execution
                WHERE execution.id = ?
                """,
                eventId,
                executionId
        );
    }

    private void insertMismatchedCanonicalEvent(
            JdbcTemplate jdbc,
            Fixture fixture,
            String eventId
    ) {
        jdbc.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    payload_json, ocorrido_em
                )
                SELECT ?, COALESCE(MAX(commit_seq), 0) + 1,
                       'RDO_EXECUTION', ?, ?, ?,
                       'RDO_SERVICE_EXECUTED', 'CORTEX_FINANCEIRO', 'ONLINE',
                       'SYNCED', 1, '{}'::jsonb, now()
                FROM cortex_evento_operacional
                """,
                eventId,
                id(),
                fixture.obraId(),
                fixture.rdoId()
        );
    }

    private void insertAcceptedExecution(
            JdbcTemplate jdbc,
            Fixture fixture,
            String executionId,
            String eventId
    ) {
        jdbc.update(
                """
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    price_version_id, quantidade_executada, unidade_medida,
                    data_execucao, status_validacao, estado_receita,
                    retrabalho, producao_rejeitada, fonte, chave_execucao,
                    unit_price_snapshot, currency, revenue_amount,
                    revenue_coverage_code, revenue_evidence_id,
                    revenue_event_id, accepted_at
                ) VALUES (?, ?, ?, 'Accepted V58 execution', ?, ?, 1.500, 'M2', ?,
                          'VALIDADA', 'RECEITA_MEDIDA', FALSE, FALSE, 'V58_IT',
                          ?, 10.0000, 'BRL', 15.00, 'ACCEPTED_EXACT',
                          ?, ?, now())
                """,
                executionId,
                fixture.rdoId(),
                fixture.obraId(),
                fixture.serviceId(),
                fixture.priceId(),
                EXECUTION_DATE,
                executionKey(executionId),
                id(),
                eventId
        );
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword()
        );
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

    private Integer checksum(JdbcTemplate jdbc, String version) {
        return jdbc.queryForObject(
                """
                SELECT checksum
                FROM flyway_schema_history
                WHERE success = TRUE AND version = ?
                """,
                Integer.class,
                version
        );
    }

    private void assertImmutableEventUpdate(
            JdbcTemplate jdbc,
            String eventId,
            String assignment
    ) {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE cortex_evento_operacional SET " + assignment + " WHERE id = ?",
                eventId
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("RDO_REVENUE_EVENT_IMMUTABLE");
    }

    private static String executionKey(String executionId) {
        return executionId.replace("-", "").repeat(2);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(
            String obraId,
            String rdoId,
            String serviceId,
            String priceId
    ) {
    }
}

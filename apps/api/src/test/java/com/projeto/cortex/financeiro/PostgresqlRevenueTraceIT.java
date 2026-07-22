package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.revenue.RevenueEvidence;
import com.projeto.cortex.financeiro.revenue.RevenueOntologyPublisher;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlRevenueTraceIT {

    private static final LocalDate EXECUTION_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_revenue_trace_it");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
    }

    @Test
    void listSumsOnlyReturnedExactEvidenceInsideAuthorizedScopeAndPeriod() {
        Fixture allowed = fixture("ALLOWED", "125.0000");
        Fixture foreign = fixture("FOREIGN", "999.0000");
        String first = acceptedExecution(allowed, "2.000", "250.00", 101L);
        String second = acceptedExecution(allowed, "0.500", "62.50", 102L);
        acceptedExecution(foreign, "1.000", "999.00", 103L);
        String mismatchedEvent = acceptedExecution(
                allowed, "4.000", "500.00", 104L
        );
        jdbc.update("""
                UPDATE cortex_evento_operacional
                SET tipo_evento = 'UNRELATED_EVENT'
                WHERE id = (
                    SELECT revenue_event_id
                    FROM execucao_servico_rdo
                    WHERE id = ?
                )
                """, mismatchedEvent);
        String mismatchedPayload = acceptedExecution(
                allowed, "8.000", "1000.00", 105L
        );
        jdbc.update("""
                UPDATE cortex_evento_operacional
                SET payload_json = jsonb_set(
                    payload_json,
                    '{priceVersionId}',
                    to_jsonb(?::text)
                )
                WHERE id = (
                    SELECT revenue_event_id
                    FROM execucao_servico_rdo
                    WHERE id = ?
                )
                """, id(), mismatchedPayload);
        String mismatchedRelatedEntities = acceptedExecution(
                allowed, "16.000", "2000.00", 106L
        );
        jdbc.update("""
                UPDATE cortex_evento_operacional
                SET entidades_relacionadas_json =
                    entidades_relacionadas_json
                    - 2
                    || jsonb_build_array(
                        jsonb_build_object('tipo', 'SERVICE', 'id', ?)
                    )
                WHERE id = (
                    SELECT revenue_event_id
                    FROM execucao_servico_rdo
                    WHERE id = ?
                )
                """, id(), mismatchedRelatedEntities);
        String extraRelatedEntity = acceptedExecution(
                allowed, "32.000", "4000.00", 107L
        );
        jdbc.update("""
                UPDATE cortex_evento_operacional
                SET entidades_relacionadas_json =
                    entidades_relacionadas_json
                    || jsonb_build_array(
                        jsonb_build_object('tipo', 'WORKSITE', 'id', ?)
                    )
                WHERE id = (
                    SELECT revenue_event_id
                    FROM execucao_servico_rdo
                    WHERE id = ?
                )
                """, id(), extraRelatedEntity);
        String mismatchedRevenue = acceptedExecution(
                allowed, "64.000", "8000.00", 108L
        );
        jdbc.update("""
                UPDATE cortex_evento_operacional
                SET payload_json = jsonb_set(
                    payload_json, '{revenue}', '123456.78'::jsonb
                )
                WHERE id = (
                    SELECT revenue_event_id
                    FROM execucao_servico_rdo
                    WHERE id = ?
                )
                """, mismatchedRevenue);
        unpricedExecution(allowed);
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        RastreioReceitaResponse response = service.buscar(
                Set.of(allowed.obraId()), null,
                EXECUTION_DATE.minusDays(1), EXECUTION_DATE.plusDays(1)
        );

        assertThat(response.totalRevenue()).isEqualByComparingTo("312.50");
        assertThat(response.evidenceCount()).isEqualTo(2);
        assertThat(response.rows()).extracting(
                RastreioReceitaResponse.RevenueEvidenceRow::executionId
        ).containsExactlyInAnyOrder(first, second);
        assertThat(response.rows()).allSatisfy(row -> {
            assertThat(row.worksiteId()).isEqualTo(allowed.obraId());
            assertThat(row.coverageCode()).isEqualTo("ACCEPTED_EXACT");
            assertThat(row.currency()).isEqualTo("BRL");
            assertThat(row.revenueEvidenceId()).isNotBlank();
            assertThat(row.revenueEventId()).isNotBlank();
            assertThat(row.eventCommitSequence()).isPositive();
        });
        assertThat(response.totalRevenue()).isEqualByComparingTo(
                response.rows().stream()
                        .map(RastreioReceitaResponse.RevenueEvidenceRow::revenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
        );
    }

    @Test
    void evidenceDrawerReturnsCanonicalOntologyChainAndConcealsForeignExecution() {
        Fixture allowed = fixture("DETAIL", "10.0000");
        Fixture foreign = fixture("DETAIL-FOREIGN", "20.0000");
        String executionId = acceptedExecution(allowed, "3.000", "30.00", 201L);
        String foreignExecution = acceptedExecution(
                foreign, "1.000", "20.00", 202L
        );
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        RastreioReceitaEvidenceResponse evidence = service.evidencia(
                Set.of(allowed.obraId()), executionId
        );

        assertThat(evidence.row().executionId()).isEqualTo(executionId);
        assertThat(evidence.ontologyLinks())
                .extracting(RastreioReceitaEvidenceResponse.OntologyLink::relationType)
                .containsExactly(
                        "EXECUTED_IN", "EXECUTES_SERVICE",
                        "GENERATES_REVENUE", "PRICED_BY"
                );
        assertThat(evidence.ontologyLinks()).allSatisfy(link -> {
            assertThat(link.sourceType()).isEqualTo("RDO_SERVICE_EXECUTED");
            assertThat(link.sourceId()).isEqualTo(executionId);
            assertThat(link.active()).isTrue();
        });

        assertThatThrownBy(() -> service.evidencia(
                Set.of(allowed.obraId()), foreignExecution
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_EVIDENCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void rejectsInvertedPeriodBeforeQueryingEvidence() {
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        assertThatThrownBy(() -> service.buscar(
                Set.of(), null,
                LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 22)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_TRACE_PERIOD_INVALID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsCanonicalStringDecimalsWrittenByTheRealOnlinePublisher()
            throws Exception {
        Fixture fixture = fixture("ONLINE-PUBLISHER", "125.0000");
        String executionId = acceptedExecution(
                fixture, "2.000", "250.00", 301L
        );
        Map<String, String> ids = jdbc.queryForMap("""
                SELECT revenue_evidence_id, revenue_event_id
                FROM execucao_servico_rdo
                WHERE id = ?
                """, executionId).entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.valueOf(entry.getValue())
                )
        );
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        AtomicReference<List<Map<String, Object>>> related =
                new AtomicReference<>();
        CortexOperationalMemoryService memory = mock(
                CortexOperationalMemoryService.class,
                invocation -> {
                    if ("registrarEventoAuditado".equals(
                            invocation.getMethod().getName()
                    )) {
                        related.set((List<Map<String, Object>>)
                                invocation.getArguments()[8]);
                        payload.set((Map<String, Object>)
                                invocation.getArguments()[14]);
                    }
                    return null;
                }
        );
        RevenueOntologyPublisher publisher = new RevenueOntologyPublisher(memory);
        publisher.publishAccepted(new RevenueEvidence(
                ids.get("revenue_evidence_id"),
                ids.get("revenue_event_id"),
                executionId,
                fixture.rdoId(),
                fixture.obraId(),
                EXECUTION_DATE,
                fixture.serviceId(),
                "TRACE.ONLINE",
                "Online service",
                fixture.priceId(),
                1,
                new BigDecimal("2.000"),
                "M2",
                "BRL",
                new BigDecimal("125.0000"),
                new BigDecimal("250.00"),
                Instant.parse("2026-07-22T12:00:00Z")
        ));
        ObjectMapper mapper = new ObjectMapper();
        jdbc.update("""
                UPDATE cortex_evento_operacional
                SET payload_json = ?::jsonb,
                    entidades_relacionadas_json = ?::jsonb
                WHERE id = ?
                """, mapper.writeValueAsString(payload.get()),
                mapper.writeValueAsString(related.get()),
                ids.get("revenue_event_id"));

        RastreioReceitaResponse response =
                new RastreioReceitaService(jdbc).buscar(
                        Set.of(fixture.obraId()), null,
                        EXECUTION_DATE, EXECUTION_DATE
                );

        assertThat(response.rows())
                .extracting(
                        RastreioReceitaResponse.RevenueEvidenceRow::executionId
                )
                .containsExactly(executionId);
        assertThat(response.totalRevenue()).isEqualByComparingTo("250.00");
    }

    private static Fixture fixture(String suffix, String unitPrice) {
        String obraId = id();
        String actorId = id();
        String rdoId = id();
        String serviceId = id();
        String priceId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId, "TRACE-" + suffix + "-" + obraId, "Obra " + suffix
        );
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'trace', 'colaborador', ?, ?, 'ALFA')
                """, actorId, actorId, "Actor " + suffix);
        jdbc.update(
                "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, ?, ?)",
                rdoId, obraId, "RDO-" + suffix, EXECUTION_DATE
        );
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, serviceId, "TRACE." + suffix + "."
                        + serviceId.substring(0, 8).toUpperCase(),
                "Service " + suffix, obraId, actorId);
        jdbc.update("""
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, fonte, criado_por
                ) VALUES (?, ?, ?, 'M2', 'BRL', 1, ?, ?, 'TRACE_IT', ?)
                """, priceId, obraId, serviceId, new BigDecimal(unitPrice),
                EXECUTION_DATE.minusDays(10), actorId);
        return new Fixture(obraId, rdoId, serviceId, priceId, unitPrice);
    }

    private static String acceptedExecution(
            Fixture fixture,
            String quantity,
            String revenue,
            long commitSequence
    ) {
        String executionId = id();
        String evidenceId = id();
        String eventId = id();
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    entidades_relacionadas_json, payload_json, ocorrido_em
                ) VALUES (?, ?, 'RDO_EXECUTION', ?, ?, ?,
                          'RDO_SERVICE_EXECUTED', 'CORTEX_FINANCEIRO', 'ONLINE',
                          'SYNCED', 1,
                          jsonb_build_array(
                              jsonb_build_object('tipo', 'RDO', 'id', ?),
                              jsonb_build_object('tipo', 'WORKSITE', 'id', ?),
                              jsonb_build_object('tipo', 'SERVICE', 'id', ?),
                              jsonb_build_object(
                                  'tipo', 'SERVICE_PRICE_VERSION', 'id', ?
                              ),
                              jsonb_build_object(
                                  'tipo', 'REVENUE_EVIDENCE', 'id', ?
                              )
                          ),
                          jsonb_build_object(
                              'schemaVersion', 1,
                              'rdoId', ?,
                              'obraId', ?,
                              'serviceId', ?,
                              'priceVersionId', ?,
                              'revenueEvidenceId', ?,
                              'acceptedQuantity', ?::numeric,
                              'unit', 'M2',
                              'unitPrice', ?::numeric,
                              'currency', 'BRL',
                              'revenue', ?::numeric,
                              'status', 'ACCEPTED'
                          ),
                          now())
                """, eventId, commitSequence, executionId, fixture.obraId(),
                fixture.rdoId(), fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), fixture.priceId(), evidenceId,
                fixture.rdoId(), fixture.obraId(), fixture.serviceId(),
                fixture.priceId(), evidenceId, quantity, fixture.unitPrice(),
                revenue);
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    price_version_id, quantidade_executada, unidade_medida,
                    data_execucao, status_validacao, estado_receita,
                    retrabalho, producao_rejeitada, fonte, chave_execucao,
                    unit_price_snapshot, currency, revenue_amount,
                    revenue_coverage_code, revenue_evidence_id,
                    revenue_event_id, accepted_at,
                    receita_operacional_estimativa, custo_realizado
                ) VALUES (?, ?, ?, 'Trace service', ?, ?, ?, 'M2', ?,
                          'VALIDADA', 'RECEITA_MEDIDA', FALSE, FALSE,
                          'TRACE_IT', ?, ?, 'BRL', ?, 'ACCEPTED_EXACT',
                          ?, ?, now(), 999999.99, 888888.88)
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), fixture.priceId(), new BigDecimal(quantity),
                EXECUTION_DATE, executionKey(executionId),
                new BigDecimal(fixture.unitPrice()), new BigDecimal(revenue),
                evidenceId, eventId);
        return executionId;
    }

    private static void unpricedExecution(Fixture fixture) {
        String executionId = id();
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    quantidade_executada, unidade_medida, data_execucao,
                    status_validacao, estado_receita, retrabalho,
                    producao_rejeitada, fonte, chave_execucao,
                    revenue_amount, revenue_coverage_code
                ) VALUES (?, ?, ?, 'Unpriced service', ?, 1, 'M2', ?,
                          'REGISTRADA', 'PRODUCAO_REGISTRADA', FALSE, FALSE,
                          'TRACE_IT', ?, 0, 'UNPRICED_REGISTERED')
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), EXECUTION_DATE, executionKey(executionId));
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
            String priceId,
            String unitPrice
    ) {
    }
}

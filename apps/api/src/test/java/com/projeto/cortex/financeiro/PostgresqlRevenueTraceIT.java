package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
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
    private static DriverManagerDataSource dataSource;

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
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void listSumsOnlyReturnedExactEvidenceInsideAuthorizedScopeAndPeriod() {
        Fixture allowed = fixture("ALLOWED", "125.0000");
        Fixture foreign = fixture("FOREIGN", "999.0000");
        String first = acceptedExecution(allowed, "2.000", "250.00", 101L);
        String second = acceptedExecution(allowed, "0.500", "62.50", 102L);
        acceptedExecution(foreign, "1.000", "999.00", 103L);
        unpricedExecution(allowed);
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        rollbackTransaction(() -> {
            acceptedExecution(
                    allowed, "4.000", "500.00", 104L,
                    ids -> jdbc.update("""
                            UPDATE cortex_evento_operacional
                            SET tipo_evento = 'UNRELATED_EVENT'
                            WHERE id = ?
                            """, ids.eventId())
            );
            acceptedExecution(
                    allowed, "8.000", "1000.00", 105L,
                    ids -> jdbc.update("""
                            UPDATE cortex_evento_operacional
                            SET payload_json = jsonb_set(
                                payload_json,
                                '{priceVersionId}',
                                to_jsonb(?::text)
                            )
                            WHERE id = ?
                            """, id(), ids.eventId())
            );
            acceptedExecution(
                    allowed, "16.000", "2000.00", 106L,
                    ids -> jdbc.update("""
                            UPDATE cortex_evento_operacional
                            SET entidades_relacionadas_json =
                                entidades_relacionadas_json
                                - 2
                                || jsonb_build_array(
                                    jsonb_build_object(
                                        'tipo', 'SERVICE', 'id', ?
                                    )
                                )
                            WHERE id = ?
                            """, id(), ids.eventId())
            );
            acceptedExecution(
                    allowed, "32.000", "4000.00", 107L,
                    ids -> jdbc.update("""
                            UPDATE cortex_evento_operacional
                            SET entidades_relacionadas_json =
                                entidades_relacionadas_json
                                || jsonb_build_array(
                                    jsonb_build_object(
                                        'tipo', 'WORKSITE', 'id', ?
                                    )
                                )
                            WHERE id = ?
                            """, id(), ids.eventId())
            );
            acceptedExecution(
                    allowed, "64.000", "8000.00", 108L,
                    ids -> jdbc.update("""
                            UPDATE cortex_evento_operacional
                            SET payload_json = jsonb_set(
                                payload_json,
                                '{revenue}',
                                '123456.78'::jsonb
                            )
                            WHERE id = ?
                            """, ids.eventId())
            );

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
        });
    }

    @Test
    void operationalResultUsesOnlyCanonicalEvidenceAndReportsPartialCoverage() {
        Fixture fixture = fixture("OPERATIONAL-PARTIAL", "25.0000");
        String valid = acceptedExecution(fixture, "2.000", "50.00", 151L);
        ResultadoOperacionalFinanceiroService service =
                new ResultadoOperacionalFinanceiroService(
                        jdbc, mock(PrevisaoFinanceiraService.class)
                );

        rollbackTransaction(() -> {
            acceptedExecution(
                    fixture, "4.000", "100.00", 152L,
                    ids -> jdbc.update("""
                            UPDATE cortex_evento_operacional
                            SET payload_json = jsonb_set(
                                payload_json,
                                '{revenue}',
                                to_jsonb('999.99'::text)
                            )
                            WHERE id = ?
                            """, ids.eventId())
            );

            ResultadoOperacionalFinanceiroResponse response = service.buscar(
                    fixture.obraId(),
                    EXECUTION_DATE.minusDays(1),
                    EXECUTION_DATE.plusDays(1)
            );

            assertThat(response.coverageCode())
                    .isEqualTo("PARTIAL_ACCEPTED_EXACT");
            assertThat(response.evidenceCount()).isOne();
            assertThat(response.producaoRealizada())
                    .isEqualByComparingTo("2.000");
            assertThat(response.receitaOperacional())
                    .isEqualByComparingTo("50.00");
            assertThat(response.receitaMedida()).isEqualByComparingTo("50.00");
            assertThat(response.servicos()).singleElement().satisfies(item -> {
                assertThat(item.quantidadeExecutada())
                        .isEqualByComparingTo("2.000");
                assertThat(item.receita()).isEqualByComparingTo("50.00");
                assertThat(item.quantidadeRdos()).isOne();
            });
            assertThat(response.receitaOperacional()).isEqualByComparingTo(
                    new RastreioReceitaService(jdbc).buscar(
                            Set.of(fixture.obraId()),
                            fixture.obraId(),
                            EXECUTION_DATE.minusDays(1),
                            EXECUTION_DATE.plusDays(1)
                    ).rows().stream()
                            .filter(row -> row.executionId().equals(valid))
                            .map(RastreioReceitaResponse.RevenueEvidenceRow::revenue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
            );
        });
    }

    @Test
    void operationalResultDistinguishesNoEvidenceFromCanonicalZeroRevenue() {
        Fixture noEvidence = fixture("OPERATIONAL-NONE", "30.0000");
        eligibleUnpricedExecution(noEvidence);
        Fixture exactZero = fixture("OPERATIONAL-ZERO", "30.0000");
        acceptedExecution(exactZero, "0.000", "0.00", 154L);
        ResultadoOperacionalFinanceiroService service =
                new ResultadoOperacionalFinanceiroService(
                        jdbc, mock(PrevisaoFinanceiraService.class)
                );

        ResultadoOperacionalFinanceiroResponse absent = service.buscar(
                noEvidence.obraId(), EXECUTION_DATE, EXECUTION_DATE
        );
        ResultadoOperacionalFinanceiroResponse zero = service.buscar(
                exactZero.obraId(), EXECUTION_DATE, EXECUTION_DATE
        );

        assertThat(absent.coverageCode()).isEqualTo("NO_ACCEPTED_EVIDENCE");
        assertThat(absent.evidenceCount()).isZero();
        assertThat(absent.producaoRealizada()).isNull();
        assertThat(absent.receitaOperacional()).isNull();
        assertThat(absent.receitaMedida()).isNull();
        assertThat(absent.servicos()).isEmpty();

        assertThat(zero.coverageCode()).isEqualTo("COMPLETE_ACCEPTED_EXACT");
        assertThat(zero.evidenceCount()).isOne();
        assertThat(zero.producaoRealizada()).isEqualByComparingTo("0.000");
        assertThat(zero.receitaOperacional()).isEqualByComparingTo("0.00");
        assertThat(zero.receitaMedida()).isEqualByComparingTo("0.00");
    }

    @Test
    void operationalResultKeepsCanonicalRevenueWhenNoPdorSnapshotExists() {
        Fixture fixture = fixture("OPERATIONAL-NO-PDOR", "40.0000");
        acceptedExecution(fixture, "2.500", "100.00", 155L);
        PrevisaoFinanceiraService previsao =
                mock(PrevisaoFinanceiraService.class);
        when(previsao.buscarAtual(fixture.obraId()))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Nenhum snapshot PDOR encontrado para a obra."
                ));
        ResultadoOperacionalFinanceiroService service =
                new ResultadoOperacionalFinanceiroService(jdbc, previsao);

        ResultadoOperacionalFinanceiroResponse response = service.buscar(
                fixture.obraId(), EXECUTION_DATE, EXECUTION_DATE
        );

        assertThat(response.coverageCode())
                .isEqualTo("COMPLETE_ACCEPTED_EXACT");
        assertThat(response.receitaOperacional())
                .isEqualByComparingTo("100.00");
        assertThat(response.pdor()).isNull();
    }

    /*
     * Apagar o RDO tira do dinheiro o que ele mediu.
     *
     * Apagar um RDO é marcar `rdo.cancelado_em` — é o que o botão da operação
     * faz e o que o `CANCELAR_RDO` da fila offline aplica no servidor. A marca
     * fica no documento e não desce para a linha de execução, que segue com
     * `cancelada = FALSE` porque ninguém cancelou a linha, cancelou-se o dia
     * inteiro. O PDOR já atravessava o RDO antes de somar; estas duas leituras,
     * não. Resultado: a projeção esquecia o RDO apagado e o realizado, na tela
     * ao lado, continuava cobrando por ele — a mesma obra respondendo duas
     * coisas diferentes sobre a mesma receita.
     */
    @Test
    void oRdoApagadoSaiDoRastreioEDoResultadoOperacional() {
        Fixture fixture = fixture("RDO-APAGADO", "125.0000");
        String executionId = acceptedExecution(fixture, "2.000", "250.00", 401L);
        publishOntologyChain(fixture, executionId);
        RastreioReceitaService rastreio = new RastreioReceitaService(jdbc);
        ResultadoOperacionalFinanceiroService operacional =
                new ResultadoOperacionalFinanceiroService(
                        jdbc, mock(PrevisaoFinanceiraService.class)
                );

        assertThat(rastreio.buscar(
                Set.of(fixture.obraId()), null, EXECUTION_DATE, EXECUTION_DATE
        ).totalRevenue()).isEqualByComparingTo("250.00");
        assertThat(operacional.buscar(
                fixture.obraId(), EXECUTION_DATE, EXECUTION_DATE
        ).receitaOperacional()).isEqualByComparingTo("250.00");
        assertThat(rastreio.evidencia(
                Set.of(fixture.obraId()), executionId
        ).row().executionId()).isEqualTo(executionId);

        jdbc.update("""
                UPDATE rdo
                SET status = 'CANCELADA', cancelado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, fixture.rdoId());

        RastreioReceitaResponse trace = rastreio.buscar(
                Set.of(fixture.obraId()), null, EXECUTION_DATE, EXECUTION_DATE
        );
        assertThat(trace.rows()).isEmpty();
        assertThat(trace.evidenceCount()).isZero();
        assertThat(trace.totalRevenue()).isEqualByComparingTo("0");

        ResultadoOperacionalFinanceiroResponse resultado = operacional.buscar(
                fixture.obraId(), EXECUTION_DATE, EXECUTION_DATE
        );
        assertThat(resultado.coverageCode()).isEqualTo("NO_ACCEPTED_EVIDENCE");
        assertThat(resultado.evidenceCount()).isZero();
        assertThat(resultado.producaoRealizada()).isNull();
        assertThat(resultado.receitaOperacional()).isNull();
        assertThat(resultado.servicos()).isEmpty();

        // A gaveta da evidência sai pela mesma consulta, e some junto.
        assertThatThrownBy(() -> rastreio.evidencia(
                Set.of(fixture.obraId()), executionId
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_EVIDENCE_NOT_FOUND_OR_FORBIDDEN");

        // Apagar é estado, não perda: restaurado, o dinheiro volta inteiro.
        jdbc.update("""
                UPDATE rdo
                SET status = 'ENVIADO', cancelado_em = NULL
                WHERE id = ?
                """, fixture.rdoId());

        assertThat(rastreio.buscar(
                Set.of(fixture.obraId()), null, EXECUTION_DATE, EXECUTION_DATE
        ).totalRevenue()).isEqualByComparingTo("250.00");
        assertThat(operacional.buscar(
                fixture.obraId(), EXECUTION_DATE, EXECUTION_DATE
        ).receitaOperacional()).isEqualByComparingTo("250.00");
    }

    @Test
    void obraArquivadaSaiDoRastreioEDoResultadoOperacional() {
        Fixture fixture = fixture("OBRA-ARQUIVADA", "125.0000");
        String executionId = acceptedExecution(fixture, "2.000", "250.00", 402L);
        publishOntologyChain(fixture, executionId);
        RastreioReceitaService rastreio = new RastreioReceitaService(jdbc);
        ResultadoOperacionalFinanceiroService operacional =
                new ResultadoOperacionalFinanceiroService(
                        jdbc, mock(PrevisaoFinanceiraService.class)
                );

        assertThat(rastreio.buscar(
                Set.of(fixture.obraId()), null, EXECUTION_DATE, EXECUTION_DATE
        ).totalRevenue()).isEqualByComparingTo("250.00");

        jdbc.update("""
                UPDATE obra
                SET arquivado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, fixture.obraId());

        RastreioReceitaResponse trace = rastreio.buscar(
                Set.of(fixture.obraId()), null, EXECUTION_DATE, EXECUTION_DATE
        );
        assertThat(trace.rows()).isEmpty();
        assertThat(trace.evidenceCount()).isZero();
        assertThat(trace.totalRevenue()).isEqualByComparingTo("0");
        assertThat(operacional.buscar(
                fixture.obraId(), EXECUTION_DATE, EXECUTION_DATE
        ).coverageCode()).isEqualTo("NO_ACCEPTED_EVIDENCE");
        assertThatThrownBy(() -> rastreio.evidencia(
                Set.of(fixture.obraId()), executionId
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_EVIDENCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void pendingQueueShowsOnlyLiveSentRegisteredWorkAndStatesExactPriceEligibility() {
        Fixture visible = fixture("PENDING-VISIBLE", "125.0000");
        Fixture cancelledRdo = fixture("PENDING-CANCELLED", "100.0000");
        Fixture archivedWorksite = fixture("PENDING-ARCHIVED", "100.0000");
        Fixture draftRdo = fixture("PENDING-DRAFT", "100.0000");
        markRdoSent(visible);
        markRdoSent(cancelledRdo);
        markRdoSent(archivedWorksite);
        jdbc.update("""
                INSERT INTO cortex_estado_entidade (
                    tipo_entidade, entidade_id, versao_entidade
                ) VALUES ('RDO', ?, 27)
                """, visible.rdoId());

        String exactPrice = registeredExecution(visible, "2.000", "M2");
        String noExactPrice = registeredExecution(visible, "3.000", "M3");
        String rework = registeredExecution(visible, "4.000", "M2");
        jdbc.update("UPDATE execucao_servico_rdo SET retrabalho = TRUE WHERE id = ?", rework);
        registeredExecution(cancelledRdo, "1.000", "M2");
        registeredExecution(archivedWorksite, "1.000", "M2");
        registeredExecution(draftRdo, "1.000", "M2");
        jdbc.update("""
                UPDATE rdo
                SET status = 'CANCELADA', cancelado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, cancelledRdo.rdoId());
        jdbc.update("""
                UPDATE obra
                SET arquivado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, archivedWorksite.obraId());

        RastreioReceitaPendenciasResponse response =
                new RastreioReceitaService(jdbc).pendentes(
                        Set.of(
                                visible.obraId(), cancelledRdo.obraId(),
                                archivedWorksite.obraId(), draftRdo.obraId()
                        ),
                        null
                );

        assertThat(response.rows()).extracting(
                RastreioReceitaPendenciasResponse.PendingRevenueExecutionRow::executionId
        ).containsExactlyInAnyOrder(exactPrice, noExactPrice);
        RastreioReceitaPendenciasResponse.PendingRevenueExecutionRow exact =
                response.rows().stream()
                        .filter(row -> row.executionId().equals(exactPrice))
                        .findFirst()
                        .orElseThrow();
        assertThat(exact.rdoId()).isEqualTo(visible.rdoId());
        assertThat(exact.rdoNumber()).isEqualTo("RDO-PENDING-VISIBLE");
        assertThat(exact.rdoEntityVersion()).isEqualTo(27L);
        assertThat(exact.quantity()).isEqualByComparingTo("2.000");
        assertThat(exact.unit()).isEqualTo("M2");
        assertThat(exact.priceState()).isEqualTo("EXACT_ACTIVE");
        assertThat(exact.priceReason()).isEqualTo("EXACT_ACTIVE_PRICE");
        assertThat(exact.currentUnitPrice()).isEqualByComparingTo("125.0000");
        RastreioReceitaPendenciasResponse.PendingRevenueExecutionRow unavailable =
                response.rows().stream()
                        .filter(row -> row.executionId().equals(noExactPrice))
                        .findFirst()
                        .orElseThrow();
        assertThat(unavailable.priceState()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.priceReason()).isEqualTo("EXACT_PRICE_NOT_FOUND");
        assertThat(unavailable.currentUnitPrice()).isNull();
    }

    @Test
    void evidenceDrawerFailsClosedWithoutPersistedOntologyRelations() {
        Fixture allowed = fixture("DETAIL-NO-RELATIONS", "10.0000");
        String executionId = acceptedExecution(allowed, "3.000", "30.00", 201L);
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        assertThatThrownBy(() -> service.evidencia(
                Set.of(allowed.obraId()), executionId
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_EVIDENCE_NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void evidenceDrawerReturnsPublishedOntologyChainAndConcealsForeignExecution() {
        Fixture allowed = fixture("DETAIL", "10.0000");
        Fixture foreign = fixture("DETAIL-FOREIGN", "20.0000");
        String executionId = acceptedExecution(allowed, "3.000", "30.00", 202L);
        String foreignExecution = acceptedExecution(
                foreign, "1.000", "20.00", 203L
        );
        publishOntologyChain(allowed, executionId);
        publishOntologyChain(foreign, foreignExecution);
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
    void evidenceDrawerRejectsEvidenceObjectScopedToAnotherWorksite() {
        Fixture allowed = fixture("DETAIL-TAMPER", "15.0000");
        Fixture foreign = fixture("DETAIL-TAMPER-FOREIGN", "25.0000");
        String executionId = acceptedExecution(allowed, "2.000", "30.00", 204L);
        publishOntologyChain(allowed, executionId);
        jdbc.update("""
                UPDATE cortex_objeto
                SET metadados_json = jsonb_set(
                    metadados_json,
                    '{obraId}',
                    to_jsonb(?::text)
                )
                WHERE tipo_entidade = 'REVENUE_EVIDENCE'
                  AND entidade_id = (
                      SELECT revenue_evidence_id
                      FROM execucao_servico_rdo
                      WHERE id = ?
                  )
                """, foreign.obraId(), executionId);
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        assertThatThrownBy(() -> service.evidencia(
                Set.of(allowed.obraId()), executionId
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
        ObjectMapper mapper = new ObjectMapper();
        String executionId = acceptedExecution(
                fixture, "2.000", "250.00", 301L,
                ids -> {
                    publisher.publishAccepted(new RevenueEvidence(
                            ids.evidenceId(),
                            ids.eventId(),
                            ids.executionId(),
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
                    try {
                        jdbc.update("""
                                UPDATE cortex_evento_operacional
                                SET payload_json = ?::jsonb,
                                    entidades_relacionadas_json = ?::jsonb
                                WHERE id = ?
                                """, mapper.writeValueAsString(payload.get()),
                                mapper.writeValueAsString(related.get()),
                                ids.eventId());
                    } catch (com.fasterxml.jackson.core.JsonProcessingException
                             exception) {
                        throw new IllegalStateException(exception);
                    }
                }
        );

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
        return acceptedExecution(
                fixture, quantity, revenue, commitSequence, ignored -> {
                }
        );
    }

    private static String acceptedExecution(
            Fixture fixture,
            String quantity,
            String revenue,
            long commitSequence,
            Consumer<AcceptedExecutionIds> beforeExecution
    ) {
        markRdoSent(fixture);
        String executionId = id();
        String evidenceId = id();
        String eventId = id();
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    entidades_relacionadas_json, estado_novo_json,
                    payload_json, ocorrido_em
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
                              'status', 'ACCEPTED',
                              'revenueEvidenceId', ?
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
                evidenceId, fixture.rdoId(), fixture.obraId(), fixture.serviceId(),
                fixture.priceId(), evidenceId, quantity, fixture.unitPrice(),
                revenue);
        jdbc.update("""
                UPDATE cortex_evento_commit_sequence
                SET ultima_commit_seq = GREATEST(ultima_commit_seq, ?)
                WHERE id = 1
                """, commitSequence);
        AcceptedExecutionIds ids = new AcceptedExecutionIds(
                executionId, evidenceId, eventId
        );
        beforeExecution.accept(ids);
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
        insertValidationDecision(fixture, executionId);
        return executionId;
    }

    private static void insertValidationDecision(
            Fixture fixture,
            String executionId
    ) {
        String decisionId = id();
        String eventId = id();
        String actorId = id();
        String mutationId = id();
        // A prova de rastreio controla o high-water mark com os commits de
        // receita. O evento de decisão só satisfaz o novo vínculo auditado e
        // deliberadamente fica fora desse cursor de fixture.
        Long commitSequence = jdbc.queryForObject(
                "SELECT COALESCE(MIN(commit_seq), 0) - 1 "
                        + "FROM cortex_evento_operacional",
                Long.class
        );
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    payload_json, ocorrido_em
                ) VALUES (?, ?, 'RDO', ?, ?, ?,
                          'RDO_EXECUCAO_VALIDADA', 'TRACE_IT', 'ONLINE',
                          'SYNCED', 13,
                          jsonb_build_object(
                              'executionId', ?,
                              'decisao', 'VALIDAR',
                              'clientMutationId', ?,
                              'mode', 'NORMAL'
                          ), now())
                """, eventId, commitSequence, fixture.rdoId(), fixture.obraId(),
                fixture.rdoId(), executionId, mutationId);
        jdbc.update("""
                INSERT INTO rdo_execucao_decisao (
                    id, execution_id, rdo_id, obra_id, decisao,
                    decidido_por, client_mutation_id, request_hash,
                    evento_id, review_mode, decidido_em
                ) VALUES (?, ?, ?, ?, 'VALIDAR', ?, ?, repeat('0', 64),
                          ?, 'NORMAL', now())
                """, decisionId, executionId, fixture.rdoId(), fixture.obraId(),
                actorId, mutationId, eventId);
    }

    private static void eligibleUnpricedExecution(Fixture fixture) {
        String executionId = id();
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    quantidade_executada, unidade_medida, data_execucao,
                    status_validacao, estado_receita, retrabalho,
                    producao_rejeitada, fonte, chave_execucao,
                    revenue_amount, revenue_coverage_code
                ) VALUES (?, ?, ?, 'Eligible unpriced service', ?, 1, 'M2', ?,
                          'VALIDADA', 'PRODUCAO_REGISTRADA', FALSE, FALSE,
                          'TRACE_IT', ?, 0, 'HISTORICAL_UNPRICED')
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), EXECUTION_DATE, executionKey(executionId));
    }

    private static void publishOntologyChain(
            Fixture fixture,
            String executionId
    ) {
        RevenueEvidence evidence = jdbc.queryForObject("""
                SELECT execution.revenue_evidence_id,
                       execution.revenue_event_id,
                       execution.rdo_id,
                       execution.obra_id,
                       execution.data_execucao,
                       execution.service_id,
                       service.codigo AS service_code,
                       service.nome AS service_name,
                       execution.price_version_id,
                       price.versao AS price_version,
                       execution.quantidade_executada,
                       execution.unidade_medida,
                       execution.currency,
                       execution.unit_price_snapshot,
                       execution.revenue_amount,
                       execution.accepted_at
                FROM execucao_servico_rdo execution
                JOIN catalogo_servico service
                  ON service.id = execution.service_id
                JOIN service_price_version price
                  ON price.id = execution.price_version_id
                 AND price.obra_id = execution.obra_id
                 AND price.service_id = execution.service_id
                WHERE execution.id = ?
                """, (rs, rowNumber) -> new RevenueEvidence(
                rs.getString("revenue_evidence_id"),
                rs.getString("revenue_event_id"),
                executionId,
                rs.getString("rdo_id"),
                rs.getString("obra_id"),
                rs.getDate("data_execucao").toLocalDate(),
                rs.getString("service_id"),
                rs.getString("service_code"),
                rs.getString("service_name"),
                rs.getString("price_version_id"),
                rs.getInt("price_version"),
                rs.getBigDecimal("quantidade_executada"),
                rs.getString("unidade_medida"),
                rs.getString("currency"),
                rs.getBigDecimal("unit_price_snapshot"),
                rs.getBigDecimal("revenue_amount"),
                rs.getTimestamp("accepted_at").toInstant()
        ), executionId);
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                new ObjectMapper(),
                mock(org.springframework.context.ApplicationEventPublisher.class)
        );
        memory.registrarObjeto(
                "RDO", fixture.rdoId(), fixture.rdoId(),
                "RDO " + fixture.rdoId(), "ATIVO", "RDO"
        );
        new RevenueOntologyPublisher(memory).publishAccepted(evidence);
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

    private static String registeredExecution(
            Fixture fixture,
            String quantity,
            String unit
    ) {
        String executionId = id();
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    quantidade_executada, unidade_medida, data_execucao,
                    status_validacao, estado_receita, retrabalho,
                    producao_rejeitada, fonte, chave_execucao,
                    revenue_amount, revenue_coverage_code
                ) VALUES (?, ?, ?, 'Registered service', ?, ?, ?, ?,
                          'REGISTRADA', 'PRODUCAO_REGISTRADA', FALSE, FALSE,
                          'TRACE_IT', ?, 0, 'UNPRICED_REGISTERED')
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), new BigDecimal(quantity), unit,
                EXECUTION_DATE, executionKey(executionId));
        return executionId;
    }

    private static void markRdoSent(Fixture fixture) {
        jdbc.update("""
                UPDATE rdo
                SET status = 'ENVIADO', enviado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, fixture.rdoId());
    }

    private static void rollbackTransaction(Runnable work) {
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        transactions.executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
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

    private record AcceptedExecutionIds(
            String executionId,
            String evidenceId,
            String eventId
    ) {
    }
}

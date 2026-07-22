package com.projeto.cortex.financeiro.revenue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.rdos.RdoContextResponse;
import com.projeto.cortex.rdos.RdoContextService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoHistoricalImportServiceCommand;
import com.projeto.cortex.rdos.RdoOperationalDetailService;
import com.projeto.cortex.rdos.RdoResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlRevenueEvidenceIT {

    private static final LocalDate RDO_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_revenue_evidence_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper mapper;

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
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void acceptedExecutionFreezesExactRevenueAndOntologyAcrossPriceHistoryAndReplay()
            throws Exception {
        Fixture fixture = fixture("ACCEPTED");
        String executionId = id();
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                executionId, fixture.serviceId(), fixture.priceId(),
                "0.333", "M2", "VALIDADA", false, false, "Original"
        );

        RdoResponse.ServicoExecutadoItem first = replace(
                fixture.rdoId(), fixture.obraId(), List.of(accepted)
        ).getFirst();

        assertThat(first.serviceId()).isEqualTo(fixture.serviceId());
        assertThat(first.priceVersionId()).isEqualTo(fixture.priceId());
        assertThat(first.unitPriceSnapshot()).isEqualByComparingTo("10.0000");
        assertThat(first.currency()).isEqualTo("BRL");
        assertThat(first.revenueAmount()).isEqualByComparingTo("3.33");
        assertThat(first.revenueCoverageCode()).isEqualTo("ACCEPTED_EXACT");
        assertThat(first.revenueEvidenceId()).isNotBlank();
        assertThat(first.revenueEventId()).isNotBlank();
        assertThat(first.acceptedAt()).isNotNull();

        insertPrice(
                fixture.obraId(), fixture.serviceId(), fixture.actorId(),
                "M2", "BRL", "12.0000", RDO_DATE.plusDays(1), null,
                2, fixture.priceId()
        );
        RdoResponse.ServicoExecutadoItem replay = replace(
                fixture.rdoId(), fixture.obraId(), List.of(accepted)
        ).getFirst();

        assertThat(replay.revenueEvidenceId()).isEqualTo(first.revenueEvidenceId());
        assertThat(replay.unitPriceSnapshot()).isEqualByComparingTo("10.0000");
        assertThat(replay.revenueAmount()).isEqualByComparingTo("3.33");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cortex_evento_operacional WHERE id = ?",
                Integer.class, first.revenueEventId()
        )).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT tipo_entidade, tipo_evento
                FROM cortex_evento_operacional WHERE id = ?
                """, first.revenueEventId()))
                .containsEntry("tipo_entidade", "RDO_EXECUTION")
                .containsEntry("tipo_evento", "RDO_SERVICE_EXECUTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_objeto
                WHERE (tipo_entidade, entidade_id) IN (
                    ('SERVICE', ?),
                    ('SERVICE_PRICE_VERSION', ?),
                    ('RDO_SERVICE_EXECUTED', ?),
                    ('REVENUE_EVIDENCE', ?)
                )
                """, Integer.class, fixture.serviceId(), fixture.priceId(),
                executionId, first.revenueEvidenceId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cortex_relacao
                WHERE origem_tipo = 'RDO_SERVICE_EXECUTED'
                  AND origem_id = ?
                  AND tipo_relacao IN (
                      'PRICED_BY', 'EXECUTED_IN', 'EXECUTES_SERVICE',
                      'GENERATES_REVENUE'
                  )
                  AND ativa = TRUE
                """, Integer.class, executionId)).isEqualTo(4);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE execucao_servico_rdo SET observacoes = 'tampered' WHERE id = ?",
                executionId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_EVIDENCE_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM execucao_servico_rdo WHERE id = ?", executionId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("RDO_REVENUE_EVIDENCE_IMMUTABLE");
    }

    @Test
    void registeredRejectedAndReworkStayUnpricedZeroAndRegisteredCanBeEditedOrRemoved()
            throws Exception {
        Fixture fixture = fixture("DRAFT");
        String registeredId = id();
        RdoResponse.ServicoExecutadoItem registered = replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        registeredId, fixture.serviceId(), fixture.priceId(),
                        "1.000", "M2", "REGISTRADA", false, false, "v1"
                ))
        ).getFirst();
        assertThat(registered.revenueEvidenceId()).isNull();
        assertThat(registered.unitPriceSnapshot()).isNull();
        assertThat(registered.revenueAmount()).isEqualByComparingTo("0.00");
        assertThat(registered.revenueCoverageCode()).isEqualTo("UNPRICED_REGISTERED");
        assertThat(jdbc.queryForMap(
                """
                SELECT price_version_id, currency, unit_price_snapshot
                FROM execucao_servico_rdo WHERE id = ?
                """, registeredId
        )).allSatisfy((column, value) -> assertThat(value).isNull());

        RdoResponse.ServicoExecutadoItem edited = replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        registeredId, fixture.serviceId(), fixture.priceId(),
                        "2.500", "M2", "REGISTRADA", false, false, "v2"
                ))
        ).getFirst();
        assertThat(edited.quantidadeExecutada()).isEqualByComparingTo("2.500");
        assertThat(edited.observacoes()).isEqualTo("v2");
        replace(fixture.rdoId(), fixture.obraId(), List.of());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, registeredId
        )).isZero();

        for (RdoCreateRequest.ServicoExecutadoItem zero : List.of(
                item(id(), fixture.serviceId(), null, "4", "M2",
                        "REJEITADA", false, true, null),
                item(id(), fixture.serviceId(), null, "5", "M2",
                        "CANCELADA", false, false, null),
                item(id(), fixture.serviceId(), null, "6", "M2",
                        "VALIDADA", true, false, null),
                item(id(), fixture.serviceId(), null, "7", "M2",
                        "VALIDADA", false, true, null)
        )) {
            RdoResponse.ServicoExecutadoItem persisted = replace(
                    fixture.rdoId(), fixture.obraId(), List.of(zero)
            ).getFirst();
            assertThat(persisted.revenueAmount()).isEqualByComparingTo("0.00");
            assertThat(persisted.revenueEvidenceId()).isNull();
            replace(fixture.rdoId(), fixture.obraId(), List.of());
        }
    }

    @Test
    void acceptedOmissionOrTamperFailsBeforeMutatingRegisteredSibling()
            throws Exception {
        Fixture fixture = fixture("TAMPER");
        String acceptedId = id();
        String registeredId = id();
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                acceptedId, fixture.serviceId(), fixture.priceId(),
                "1", "M2", "VALIDADA", false, false, "accepted"
        );
        RdoCreateRequest.ServicoExecutadoItem registered = item(
                registeredId, fixture.serviceId(), fixture.priceId(),
                "2", "M2", "REGISTRADA", false, false, "registered"
        );
        replace(fixture.rdoId(), fixture.obraId(), List.of(accepted, registered));

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        registeredId, fixture.serviceId(), fixture.priceId(),
                        "9", "M2", "REGISTRADA", false, false, "changed"
                ))
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_ACCEPTED_EXECUTION_OMITTED");
        assertThat(quantity(registeredId)).isEqualByComparingTo("2.000");

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(
                        item(acceptedId, fixture.serviceId(), fixture.priceId(),
                                "1.001", "M2", "VALIDADA", false, false, "accepted"),
                        registered
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_ACCEPTED_EXECUTION_IMMUTABLE");
        assertThat(quantity(acceptedId)).isEqualByComparingTo("1.000");
        assertThat(quantity(registeredId)).isEqualByComparingTo("2.000");

        RdoCreateRequest.ServicoExecutadoItem legacyTamper =
                new RdoCreateRequest.ServicoExecutadoItem(
                        accepted.id(), accepted.serviceId(), accepted.priceVersionId(),
                        accepted.servicoNome(), id(), accepted.quantidadeExecutada(),
                        accepted.unidade(), accepted.trechoInicial(), accepted.trechoFinal(),
                        accepted.localizacao(), accepted.turno(), accepted.statusValidacao(),
                        accepted.retrabalho(), accepted.producaoRejeitada(),
                        accepted.observacoes()
                );
        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(legacyTamper, registered)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_ACCEPTED_EXECUTION_IMMUTABLE");
    }

    @Test
    void mixedPayloadRollsBackOnMissingCrossWorksiteOrCancelledPrice()
            throws Exception {
        Fixture fixture = fixture("ROLLBACK");
        Fixture foreign = fixture("FOREIGN");
        String validExecution = id();

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(
                        item(validExecution, fixture.serviceId(), fixture.priceId(),
                                "1", "M2", "VALIDADA", false, false, null),
                        item(id(), foreign.serviceId(), foreign.priceId(),
                                "1", "M2", "VALIDADA", false, false, null)
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_PRICE_SCOPE_INVALID");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE rdo_id = ?",
                Integer.class, fixture.rdoId()
        )).isZero();

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        id(), fixture.serviceId(), null,
                        "1", "M2", "VALIDADA", false, false, null
                ))
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_PRICE_REQUIRED");

        cancelPrice(fixture.priceId(), fixture.obraId(), fixture.actorId(), RDO_DATE);
        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(item(
                        id(), fixture.serviceId(), fixture.priceId(),
                        "1", "M2", "VALIDADA", false, false, null
                ))
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_PRICE_STALE");
    }

    @Test
    void concurrentExactReplayKeepsOneEvidenceAndOneEvent() throws Exception {
        Fixture fixture = fixture("CONCURRENT");
        String executionId = id();
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                executionId, fixture.serviceId(), fixture.priceId(),
                "10", "M2", "VALIDADA", false, false, null
        );
        RdoResponse.ServicoExecutadoItem created = replace(
                fixture.rdoId(), fixture.obraId(), List.of(accepted)
        ).getFirst();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<RdoResponse.ServicoExecutadoItem>> first = executor.submit(() -> {
                start.await();
                return replace(fixture.rdoId(), fixture.obraId(), List.of(accepted));
            });
            Future<List<RdoResponse.ServicoExecutadoItem>> second = executor.submit(() -> {
                start.await();
                return replace(fixture.rdoId(), fixture.obraId(), List.of(accepted));
            });
            start.countDown();
            assertThat(first.get()).singleElement();
            assertThat(second.get()).singleElement();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cortex_evento_operacional WHERE id = ?",
                Integer.class, created.revenueEventId()
        )).isOne();
    }

    @Test
    void ontologyPublisherFailureRollsBackExecutionEvidenceAndEvent() throws Exception {
        Fixture fixture = fixture("ONTOLOGY-ROLLBACK");
        RdoCreateRequest.ServicoExecutadoItem accepted = item(
                id(), fixture.serviceId(), fixture.priceId(),
                "2", "M2", "VALIDADA", false, false, null
        );
        RevenueOntologyPublisher publisher = mock(RevenueOntologyPublisher.class);
        doThrow(new IllegalStateException("ontology unavailable"))
                .when(publisher).publishAccepted(org.mockito.ArgumentMatchers.any());
        RdoOperationalDetailService service = new RdoOperationalDetailService(
                jdbc, memoryService(), publisher
        );

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored ->
                service.substituirDetalhes(
                        fixture.rdoId(), fixture.obraId(), null, RDO_DATE,
                        "DIURNO", List.of(accepted), List.of()
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ontology unavailable");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, accepted.id()
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cortex_evento_operacional WHERE entidade_id = ?",
                Integer.class, accepted.id()
        )).isZero();
    }

    @Test
    void contextReturnsRealCatalogPriceChoicesExactCoverageAndRevision() {
        Fixture fixture = fixture("CONTEXT");

        RdoContextResponse context = new RdoContextService(jdbc)
                .buscarContexto(fixture.obraId(), RDO_DATE);

        assertThat(context.serviceCatalog())
                .filteredOn(service -> service.id().equals(fixture.serviceId()))
                .singleElement()
                .satisfies(service -> assertThat(service.priceChoices())
                        .singleElement()
                        .satisfies(price -> {
                            assertThat(price.id()).isEqualTo(fixture.priceId());
                            assertThat(price.unitPrice()).isEqualByComparingTo("10.0000");
                            assertThat(price.currency()).isEqualTo("BRL");
                        }));
        assertThat(context.coverage().serviceCatalog().status()).isEqualTo("COMPLETE");
        assertThat(context.coverage().priceCatalog().status()).isEqualTo("COMPLETE");
        assertThat(context.coverage().serviceCatalog().complete()).isTrue();
        assertThat(context.coverage().priceCatalog().complete()).isTrue();
        assertThat(context.freshness().catalogRevision()).isPositive();
    }

    @Test
    void historicalImportIsProvenanceGatedAtomicAndUnavailableToNormalRdoPayload()
            throws Exception {
        Fixture fixture = fixture("HISTORICAL");
        String importId = id();
        String executionId = id();
        String legacyItemId = id();
        insertHistoricalImport(importId, fixture.rdoId());
        insertLegacyContractItem(legacyItemId, fixture);
        RdoCreateRequest.ServicoExecutadoItem normalPayload =
                new RdoCreateRequest.ServicoExecutadoItem(
                        executionId, null, null, "Legacy asphalt service", null,
                        new BigDecimal("3.250"), "M2", null, null,
                        "KM 2", "DIURNO", "REGISTRADA", false, false,
                        "historical source"
                );

        assertThatThrownBy(() -> replace(
                fixture.rdoId(), fixture.obraId(), List.of(normalPayload)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_REVENUE_SERVICE_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isZero();

        RdoHistoricalImportServiceCommand command = historicalCommand(
                importId, fixture, executionId, legacyItemId
        );
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            detailService().substituirServicoImportadoHistorico(command);
            throw new IllegalStateException("force outer rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("force outer rollback");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo WHERE id = ?",
                Integer.class, executionId
        )).isZero();

        RdoResponse.ServicoExecutadoItem imported = transactions.execute(
                ignored -> detailService()
                        .substituirServicoImportadoHistorico(command)
        );
        assertThat(imported).isNotNull();
        assertThat(imported.serviceId()).isNull();
        assertThat(imported.priceVersionId()).isNull();
        assertThat(imported.unitPriceSnapshot()).isNull();
        assertThat(imported.currency()).isNull();
        assertThat(imported.revenueAmount()).isEqualByComparingTo("0.00");
        assertThat(imported.revenueCoverageCode())
                .isEqualTo("HISTORICAL_UNPRICED");
        assertThat(imported.revenueEvidenceId()).isNull();
        assertThat(imported.revenueEventId()).isNull();
        assertThat(imported.acceptedAt()).isNull();
        assertThat(jdbc.queryForMap("""
                SELECT service_id, price_version_id, unit_price_snapshot, currency,
                       revenue_evidence_id, revenue_event_id, accepted_at
                FROM execucao_servico_rdo WHERE id = ?
                """, executionId)).allSatisfy(
                        (column, value) -> assertThat(value)
                                .as("%s must be null", column).isNull()
                );
        assertThat(jdbc.queryForMap("""
                SELECT revenue_amount, revenue_coverage_code, fonte
                FROM execucao_servico_rdo WHERE id = ?
                """, executionId))
                .containsEntry("revenue_coverage_code", "HISTORICAL_UNPRICED")
                .containsEntry("fonte", "IMPORTACAO_HISTORICA")
                .containsEntry("revenue_amount", new BigDecimal("0.00"));

        RdoHistoricalImportServiceCommand forged = new RdoHistoricalImportServiceCommand(
                command.rdoId(), command.obraId(), id(), command.fileName(),
                command.sheetName(), command.rowNumber(), command.executionId(),
                command.serviceName(), command.itemContractId(), command.quantity(),
                command.unit(), command.initialSegment(), command.finalSegment(),
                command.location(), command.turn(), command.observations()
        );
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                ignored -> detailService().substituirServicoImportadoHistorico(forged)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO_HISTORICAL_IMPORT_PROVENANCE_INVALID");
        assertThat(quantity(executionId)).isEqualByComparingTo("3.250");
    }

    private static List<RdoResponse.ServicoExecutadoItem> replace(
            String rdoId,
            String obraId,
            List<RdoCreateRequest.ServicoExecutadoItem> services
    ) {
        return transactions.execute(status -> detailService().substituirDetalhes(
                rdoId, obraId, null, RDO_DATE, "DIURNO", services, List.of()
        ).servicosExecutados());
    }

    private static RdoOperationalDetailService detailService() {
        return new RdoOperationalDetailService(jdbc, memoryService());
    }

    private static CortexOperationalMemoryService memoryService() {
        return new CortexOperationalMemoryService(
                jdbc, mapper, mock(ApplicationEventPublisher.class)
        );
    }

    private static Fixture fixture(String suffix) {
        String obraId = id();
        String actorId = id();
        String rdoId = id();
        String serviceId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId, "CTR-" + suffix + "-" + obraId, "Obra " + suffix
        );
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, ?, 'ALFA')
                """, actorId, actorId, "Actor " + suffix);
        jdbc.update(
                "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, ?, ?)",
                rdoId, obraId, "RDO-" + suffix, RDO_DATE
        );
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, serviceId, "SERVICE." + suffix + "."
                        + serviceId.substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                "Service " + suffix, obraId, actorId);
        String priceId = insertPrice(
                obraId, serviceId, actorId, "M2", "BRL", "10.0000",
                RDO_DATE.minusDays(10), null, 1, null
        );
        return new Fixture(obraId, actorId, rdoId, serviceId, priceId);
    }

    private static void insertHistoricalImport(String importId, String rdoId) {
        jdbc.update("""
                INSERT INTO importacao_rdo (
                    id, nome_arquivo, hash_arquivo, tamanho_bytes, status
                ) VALUES (?, 'legacy.xlsx', ?, 128, 'VALIDADA')
                """, importId, "a".repeat(64));
        jdbc.update("""
                UPDATE rdo
                SET fonte_criacao = 'IMPORTACAO_HISTORICA',
                    importacao_rdo_id = ?, fonte_arquivo = 'legacy.xlsx',
                    aba_origem = 'RDO', linha_origem = 42
                WHERE id = ?
                """, importId, rdoId);
    }

    private static RdoHistoricalImportServiceCommand historicalCommand(
            String importId,
            Fixture fixture,
            String executionId,
            String legacyItemId
    ) {
        return new RdoHistoricalImportServiceCommand(
                fixture.rdoId(), fixture.obraId(), importId, "legacy.xlsx",
                "RDO", 42, executionId, "Legacy asphalt service", legacyItemId,
                new BigDecimal("3.250"), null, null, null, "KM 2",
                "DIURNO", "historical source"
        );
    }

    private static void insertLegacyContractItem(String itemId, Fixture fixture) {
        jdbc.update("""
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total, vigencia_inicio, status
                ) VALUES (?, ?, 'LEGACY', ?, 'Legacy item', 'M2', 1, 0, 0, ?, 'ATIVO')
                """, itemId, fixture.obraId(), "LEGACY." + itemId,
                RDO_DATE.minusDays(1));
    }

    private static String insertPrice(
            String obraId,
            String serviceId,
            String actorId,
            String unit,
            String currency,
            String unitPrice,
            LocalDate validFrom,
            LocalDate validTo,
            int version,
            String supersedesId
    ) {
        String priceId = id();
        jdbc.update("""
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, vigencia_fim,
                    fonte, supersedes_id, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RDO_REVENUE_IT', ?, ?)
                """, priceId, obraId, serviceId, unit, currency, version,
                new BigDecimal(unitPrice), validFrom, validTo, supersedesId, actorId);
        return priceId;
    }

    private static void cancelPrice(
            String priceId,
            String obraId,
            String actorId,
            LocalDate cancellationDate
    ) {
        jdbc.update("""
                INSERT INTO service_price_version_cancellation (
                    id, price_version_id, obra_id, vigencia_cancelamento,
                    motivo, criado_por
                ) VALUES (?, ?, ?, ?, 'Test cancellation', ?)
                """, id(), priceId, obraId, cancellationDate, actorId);
    }

    private static RdoCreateRequest.ServicoExecutadoItem item(
            String executionId,
            String serviceId,
            String priceVersionId,
            String quantity,
            String unit,
            String status,
            boolean rework,
            boolean rejected,
            String observations
    ) throws Exception {
        return mapper.readValue("""
                {
                  "id":"%s",
                  "serviceId":"%s",
                  "priceVersionId":%s,
                  "servicoNome":"client supplied name must be ignored",
                  "quantidadeExecutada":%s,
                  "unidade":"%s",
                  "localizacao":"km 1+000",
                  "turno":"DIURNO",
                  "statusValidacao":"%s",
                  "retrabalho":%s,
                  "producaoRejeitada":%s,
                  "observacoes":%s
                }
                """.formatted(
                executionId,
                serviceId,
                priceVersionId == null ? "null" : "\"" + priceVersionId + "\"",
                quantity,
                unit,
                status,
                rework,
                rejected,
                observations == null ? "null" : "\"" + observations + "\""
        ), RdoCreateRequest.ServicoExecutadoItem.class);
    }

    private static BigDecimal quantity(String executionId) {
        return jdbc.queryForObject(
                "SELECT quantidade_executada FROM execucao_servico_rdo WHERE id = ?",
                BigDecimal.class, executionId
        );
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(
            String obraId,
            String actorId,
            String rdoId,
            String serviceId,
            String priceId
    ) {
    }
}

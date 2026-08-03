package com.projeto.cortex.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialGrantRepository;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.catalog.CreateServiceCommand;
import com.projeto.cortex.financeiro.catalog.CreateServicePriceCommand;
import com.projeto.cortex.financeiro.catalog.PostgresqlServiceCatalogOntologyPublisher;
import com.projeto.cortex.financeiro.catalog.PostgresqlServicePriceCatalogRepository;
import com.projeto.cortex.financeiro.catalog.ServiceCatalogEntry;
import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogService;
import com.projeto.cortex.financeiro.catalog.ServicePriceVersion;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import com.projeto.cortex.ontology.graph.GraphProjectionCatchUpService;
import com.projeto.cortex.ontology.graph.GraphProjectionService;
import com.projeto.cortex.ontology.graph.OperationalGraphProjector;
import com.projeto.cortex.ontology.graph.PostgresqlCommittedOperationalEventReader;
import com.projeto.cortex.ontology.graph.PostgresqlOntologyGraphRepository;
import com.projeto.cortex.pdor.PdorApplicationService;
import com.projeto.cortex.pdor.PdorController;
import com.projeto.cortex.pdor.PdorExecutionInitiatorResolver;
import com.projeto.cortex.pdor.PdorResultadoResponse;
import com.projeto.cortex.pdor.PdorSnapshotPublicationService;
import com.projeto.cortex.pdor.PdorSnapshotRepository;
import com.projeto.cortex.pdor.RealPdorInputLoader;
import com.projeto.cortex.rdos.RdoAssetEligibilityService;
import com.projeto.cortex.rdos.RdoAttachmentService;
import com.projeto.cortex.rdos.RdoContextResponse;
import com.projeto.cortex.rdos.RdoContextService;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoMemoryPublisher;
import com.projeto.cortex.rdos.RdoOperationalDetailService;
import com.projeto.cortex.rdos.RdoOperationalEventService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import com.projeto.cortex.sync.RdoSyncOperationHandler;
import com.projeto.cortex.sync.SyncDeviceRequest;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlCortex3FlowIT {

    private static final LocalDate FLOW_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_3_flow_it");

    private static DriverManagerDataSource dataSource;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactions;
    private static JdbcTemplate jdbc;
    private static ObjectMapper mapper;

    @BeforeAll
    static void migrateCleanPostgresql() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterEach
    void clearAuthentication() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void scopedOfflineReplayProducesOneRevenueGraphAndCostFreePdor()
            throws Exception {
        Obra worksite = Obra.criar(
                "CTR-CORTEX-3", "CW-CORTEX-3", "C3-FLOW",
                "Obra Cortex 3", "Cliente fixture", null,
                "Sao Paulo", "SP", "SP-000", "ATIVA", "TEST", null, null
        );
        insertWorksite(worksite);
        String foreignWorksiteId = insertForeignWorksite();
        String actorId = insertCollaborator("Operadora scoped", "BETA");
        String previousWorkerId = insertCollaborator("Equipe anterior", "BETA");
        String currentWorkerId = insertCollaborator("Equipe atual", "BETA");
        linkToWorksite(actorId, worksite.getId(), "APONTADOR");
        linkToWorksite(previousWorkerId, worksite.getId(), "OPERACIONAL");
        linkToWorksite(currentWorkerId, worksite.getId(), "OPERACIONAL");
        authenticate(actorId);

        CurrentUserService currentUsers = new CurrentUserService(
                jdbc, new MockEnvironment(), false
        );
        // O escopo de obra abriu: a obra "de outrem" existe no catálogo e por
        // isso entra também. O que este fluxo continua provando adiante é o que
        // não abriu junto — a receita e o PDOR seguem presos à concessão
        // financeira explícita, que é outra porta.
        assertThat(currentUsers.allowedObraIds(actorId))
                .contains(Set.of(worksite.getId(), foreignWorksiteId));
        assertThat(currentUsers.podeAcessarObra(actorId, worksite.getId()))
                .isTrue();
        assertThat(currentUsers.podeAcessarObra(actorId, foreignWorksiteId))
                .isTrue();

        String previousRdoId = id();
        String previousWorkforceItemId = id();
        insertPreviousRdo(previousRdoId, worksite.getId());
        insertPreviousWorkforce(
                previousWorkforceItemId, previousRdoId, previousWorkerId
        );

        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        ServicePriceCatalogService catalog = new ServicePriceCatalogService(
                new PostgresqlServicePriceCatalogRepository(jdbc),
                new PostgresqlServiceCatalogOntologyPublisher(memory),
                mock(ObraOperabilityGuard.class)
        );
        String serviceId = id();
        String priceId = id();
        ServiceCatalogEntry service = inTransaction(() -> catalog.createService(
                worksite.getId(),
                actorId,
                new CreateServiceCommand(
                        serviceId,
                        id(),
                        "CORTEX3.PAVEMENT",
                        "Pavimento executado",
                        "Servico canonico do fluxo Cortex 3"
                )
        ));
        ServicePriceVersion price = inTransaction(() -> catalog.createPrice(
                worksite.getId(),
                actorId,
                service.id(),
                new CreateServicePriceCommand(
                        priceId,
                        id(),
                        "M2",
                        "BRL",
                        new BigDecimal("125.0000"),
                        new BigDecimal("10.000"),
                        FLOW_DATE.minusDays(30),
                        null,
                        "CONTRATO_MEDIDO"
                )
        ));
        insertContractItem(worksite.getId(), service.code());
        FinancialUnitRepository units = new FinancialUnitRepository(jdbc);
        FinancialGrantRepository grants = new FinancialGrantRepository(jdbc);
        String worksiteUnitId = id();
        units.insertWorksite(
                worksiteUnitId,
                worksite.getId(),
                "UC-CORTEX-3",
                "Unidade da obra Cortex 3",
                actorId
        );
        grants.insertUnit(
                id(),
                actorId,
                worksiteUnitId,
                worksite.getId(),
                FinancialPermission.FINANCEIRO_ADMINISTRAR,
                "Cenario clean PostgreSQL Cortex 3",
                actorId,
                LocalDateTime.of(2026, 7, 22, 9, 0)
        );
        FinancialAccessService financialAccess = new FinancialAccessService(
                currentUsers, grants, units
        );

        SyncService sync = syncService(currentUsers, financialAccess, memory);
        String deviceId = id();
        assertThat(sync.registrarDispositivo(new SyncDeviceRequest(
                deviceId, "Browser offline Cortex 3", "WEB", actorId
        )).usuarioId()).isEqualTo(actorId);

        RdoContextResponse creationContext = new RdoContextService(jdbc, mapper)
                .buscarContexto(worksite.getId(), FLOW_DATE);
        assertThat(creationContext.previousRdo().id()).isEqualTo(previousRdoId);
        assertThat(creationContext.previousWorkforce())
                .extracting(RdoContextResponse.PreviousWorkforceItem::collaboratorId)
                .containsExactly(previousWorkerId);
        assertThat(creationContext.serviceCatalog())
                .filteredOn(item -> item.id().equals(service.id()))
                .singleElement()
                .satisfies(item -> assertThat(item.priceChoices())
                        .extracting(RdoContextResponse.ServicePriceChoice::id)
                        .containsExactly(price.id()));

        String newRdoId = id();
        String currentWorkforceItemId = id();
        String executionId = id();
        ObjectNode payload = rdoPayload(
                newRdoId,
                worksite.getId(),
                previousRdoId,
                creationContext.provenance().receiptVersion(),
                actorId,
                currentWorkerId,
                currentWorkforceItemId,
                executionId,
                service,
                price
        );
        SyncPushRequest request = canonicalCreateRequest(
                deviceId, actorId, worksite.getId(), newRdoId, payload
        );

        SyncPushResponse first = sync.push(request);
        SyncPushResponse replay = sync.push(request);

        assertApplied(first, newRdoId);
        assertApplied(replay, newRdoId);
        assertThat(replay.resultados().getFirst().eventoServidorCommitSeq())
                .isEqualTo(first.resultados().getFirst().eventoServidorCommitSeq());
        String mutationId = request.mutacoes().getFirst().clientMutationId();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sync_mutacao_cliente "
                        + "WHERE proprietario_id = ? AND client_mutation_id = ?",
                Integer.class,
                actorId,
                mutationId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ? AND previous_rdo_id = ?",
                Integer.class,
                newRdoId,
                previousRdoId
        )).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM cortex_evento_operacional
                WHERE tipo_evento = 'RDO_CRIADO'
                  AND entidade_id = ?
                  AND obra_id = ?
                """, Integer.class, newRdoId, worksite.getId())).isOne();
        assertThat(jdbc.queryForList(
                "SELECT colaborador_id FROM rdo_mao_obra WHERE rdo_id = ?",
                String.class,
                newRdoId
        )).containsExactlyInAnyOrder(actorId, currentWorkerId)
                .doesNotContain(previousWorkerId);

        Map<String, Object> execution = jdbc.queryForMap("""
                SELECT revenue_amount, revenue_coverage_code,
                       revenue_evidence_id, revenue_event_id,
                       receita_operacional_estimativa, custo_realizado
                FROM execucao_servico_rdo
                WHERE id = ? AND rdo_id = ?
                """, executionId, newRdoId);
        assertThat(execution)
                .containsEntry("revenue_amount", new BigDecimal("250.00"))
                .containsEntry("revenue_coverage_code", "ACCEPTED_EXACT");
        assertThat(execution.get("receita_operacional_estimativa")).isNull();
        assertThat(execution.get("custo_realizado")).isNull();
        String revenueEvidenceId = execution.get("revenue_evidence_id").toString();
        String revenueEventId = execution.get("revenue_event_id").toString();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execucao_servico_rdo "
                        + "WHERE rdo_id = ? AND revenue_evidence_id = ?",
                Integer.class,
                newRdoId,
                revenueEvidenceId
        )).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM cortex_evento_operacional
                WHERE id = ?
                  AND tipo_evento = 'RDO_SERVICE_EXECUTED'
                  AND entidade_id = ?
                  AND obra_id = ?
                  AND rdo_id = ?
                """, Integer.class, revenueEventId, executionId,
                worksite.getId(), newRdoId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM cortex_relacao
                WHERE origem_tipo = 'RDO_SERVICE_EXECUTED'
                  AND origem_id = ?
                  AND destino_tipo = 'REVENUE_EVIDENCE'
                  AND destino_id = ?
                  AND tipo_relacao = 'GENERATES_REVENUE'
                  AND ativa = TRUE
                """, Integer.class, executionId, revenueEvidenceId)).isOne();

        projectGraph();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM ontology_relations relation
                JOIN ontology_entities source
                  ON source.id = relation.source_entity_id
                JOIN ontology_entities target
                  ON target.id = relation.target_entity_id
                WHERE relation.relation_type = 'GENERATES_REVENUE'
                  AND source.entity_type = 'RDO_EXECUTION'
                  AND source.external_ref_id = ?
                  AND target.entity_type = 'REVENUE_EVIDENCE'
                  AND target.external_ref_id = ?
                """, Integer.class, executionId, revenueEvidenceId)).isOne();

        long evidenceHighWaterMark = jdbc.queryForObject(
                "SELECT ultima_commit_seq FROM cortex_evento_commit_sequence WHERE id = 1",
                Long.class
        );
        ObraRepository worksites = mock(ObraRepository.class);
        when(worksites.findByIdentificador(worksite.getId()))
                .thenReturn(List.of(worksite));
        PdorSnapshotRepository snapshots = new PdorSnapshotRepository(
                jdbc,
                mapper
        );
        PdorApplicationService pdorService = new PdorApplicationService(
                worksites,
                new RealPdorInputLoader(jdbc),
                snapshots,
                new PdorSnapshotPublicationService(
                        snapshots,
                        mock(ObraOperabilityGuard.class)
                ),
                mapper,
                memory,
                new PdorExecutionInitiatorResolver(currentUsers)
        );
        PdorController pdorController = new PdorController(
                pdorService, financialAccess
        );
        assertThatThrownBy(() -> pdorController.calcular(
                foreignWorksiteId, FLOW_DATE, "API", revenueEventId
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value()
                ).isEqualTo(403));
        PdorResultadoResponse pdor = pdorController.calcular(
                worksite.getId(), FLOW_DATE, "API", revenueEventId
        );

        assertThat(pdor.statusExecucao()).isEqualTo("SUCCESS");
        assertThat(pdor.algorithmVersion()).isEqualTo("PDOR-REVENUE-1");
        assertThat(pdor.evidenceIds())
                .containsExactly(revenueEvidenceId)
                .isSorted();
        assertThat(pdor.evidenceHighWaterMark()).isEqualTo(evidenceHighWaterMark);
        assertThat(pdor.coverageCode()).isEqualTo("COMPLETE_ACCEPTED_EXACT");
        assertThat(pdor.assumptions()).isNotNull().isNotEmpty();
        assertThat(pdor.executedAtUtc()).isNotNull();
        assertThat(pdor.executedAtUtc().toString()).endsWith("Z");
        assertThat(jdbc.queryForObject(
                "SELECT executed_at_utc FROM pdor_snapshot WHERE id = ?",
                OffsetDateTime.class,
                pdor.id()
        ).toInstant()).isEqualTo(pdor.executedAtUtc());
        assertThat(pdor.current()).isTrue();
        assertThat(pdor.stale()).isFalse();
        assertThat(pdor.inputs().path("quantityMetric").asText())
                .isEqualTo("SERVICE_CATALOG_CONTRACT");
        assertThat(pdor.inputs().path("remainingContractedQuantity").decimalValue())
                .isEqualByComparingTo("8.000");

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'pdor_snapshot'
                  AND column_name IN (
                      'custo_realizado', 'custo_comprometido',
                      'custo_previsto_final', 'margem_atual',
                      'margem_prevista', 'margem_percentual'
                  )
                """, Integer.class)).isZero();
        String serializedPdor = mapper.valueToTree(pdor).toString().toLowerCase();
        assertThat(serializedPdor)
                .doesNotContain("custo")
                .doesNotContain("cost")
                .doesNotContain("margem")
                .doesNotContain("margin")
                .doesNotContain("receita_operacional_estimativa");
    }

    @Test
    void offlineManualWorkforceReplaysOnceAndCarriesForwardWithoutGrantingAccess()
            throws Exception {
        String worksiteId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                worksiteId,
                "CTR-MANUAL-" + worksiteId,
                "Obra com equipe manual"
        );
        String actorId = insertCollaborator("Apontador da equipe manual", "BETA");
        linkToWorksite(actorId, worksiteId, "APONTADOR");
        authenticate(actorId);

        CurrentUserService currentUsers = new CurrentUserService(
                jdbc, new MockEnvironment(), false
        );
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        SyncService sync = syncService(
                currentUsers,
                mock(FinancialAccessService.class),
                memory
        );
        String deviceId = id();
        sync.registrarDispositivo(new SyncDeviceRequest(
                deviceId, "Dispositivo da equipe manual", "WEB", actorId
        ));

        int collaboratorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM colaborador",
                Integer.class
        );
        int linksBefore = jdbc.queryForObject(
                "SELECT count(*) FROM vinculo_colaborador_obra",
                Integer.class
        );
        int identitiesBefore = jdbc.queryForObject(
                "SELECT count(*) FROM auth_identity",
                Integer.class
        );
        RdoContextService contextService = new RdoContextService(jdbc, mapper);
        RdoContextResponse firstContext = contextService.buscarContexto(
                worksiteId,
                FLOW_DATE
        );
        assertThat(firstContext.previousRdo()).isNull();

        String firstRdoId = id();
        String firstWorkforceItemId = id();
        SyncPushRequest firstRequest = canonicalCreateRequest(
                deviceId,
                actorId,
                worksiteId,
                firstRdoId,
                manualWorkforcePayload(
                        firstRdoId,
                        worksiteId,
                        null,
                        firstContext.provenance().receiptVersion(),
                        FLOW_DATE,
                        firstWorkforceItemId,
                        null
                )
        );

        SyncPushResponse first = sync.push(firstRequest);
        SyncPushResponse firstReplay = sync.push(firstRequest);

        assertApplied(first, firstRdoId);
        assertApplied(firstReplay, firstRdoId);
        assertThat(firstReplay.resultados().getFirst().eventoServidorCommitSeq())
                .isEqualTo(first.resultados().getFirst().eventoServidorCommitSeq());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ?",
                Integer.class,
                firstRdoId
        )).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT colaborador_id, nome_colaborador, cargo, origem_item_id
                FROM rdo_mao_obra
                WHERE id = ? AND rdo_id = ?
                """, firstWorkforceItemId, firstRdoId)).satisfies(row -> {
            assertThat(row.get("colaborador_id")).isNull();
            assertThat(row.get("nome_colaborador")).isEqualTo("Maria Servente");
            assertThat(row.get("cargo")).isEqualTo("SERVENTE");
            assertThat(row.get("origem_item_id")).isNull();
        });
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM sync_mutacao_cliente
                WHERE proprietario_id = ? AND client_mutation_id = ?
                """,
                Integer.class,
                actorId,
                firstRequest.mutacoes().getFirst().clientMutationId()
        )).isOne();

        RdoContextResponse nextContext = contextService.buscarContexto(
                worksiteId,
                FLOW_DATE.plusDays(1)
        );
        assertThat(nextContext.previousRdo().id()).isEqualTo(firstRdoId);
        assertThat(nextContext.previousWorkforce())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.sourceItemId()).isEqualTo(firstWorkforceItemId);
                    assertThat(item.collaboratorId()).isNull();
                    assertThat(item.nameSnapshot()).isEqualTo("Maria Servente");
                    assertThat(item.availability()).isEqualTo("AVAILABLE");
                });

        String secondRdoId = id();
        String secondWorkforceItemId = id();
        SyncPushRequest secondRequest = canonicalCreateRequest(
                deviceId,
                actorId,
                worksiteId,
                secondRdoId,
                manualWorkforcePayload(
                        secondRdoId,
                        worksiteId,
                        firstRdoId,
                        nextContext.provenance().receiptVersion(),
                        FLOW_DATE.plusDays(1),
                        secondWorkforceItemId,
                        firstWorkforceItemId
                )
        );

        SyncPushResponse second = sync.push(secondRequest);
        SyncPushResponse secondReplay = sync.push(secondRequest);

        assertApplied(second, secondRdoId);
        assertApplied(secondReplay, secondRdoId);
        assertThat(secondReplay.resultados().getFirst().eventoServidorCommitSeq())
                .isEqualTo(second.resultados().getFirst().eventoServidorCommitSeq());
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rdo_mao_obra
                WHERE id = ? AND rdo_id = ?
                  AND colaborador_id IS NULL
                  AND nome_colaborador = 'Maria Servente'
                  AND origem_item_id = ?
                """,
                Integer.class,
                secondWorkforceItemId,
                secondRdoId,
                firstWorkforceItemId
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM sync_mutacao_cliente
                WHERE proprietario_id = ?
                  AND client_mutation_id IN (?, ?)
                """,
                Integer.class,
                actorId,
                firstRequest.mutacoes().getFirst().clientMutationId(),
                secondRequest.mutacoes().getFirst().clientMutationId()
        )).isEqualTo(2);

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_relacao
                WHERE origem_tipo = 'RDO'
                  AND destino_tipo = 'RDO_MAO_OBRA'
                  AND tipo_relacao = 'REGISTRA_MAO_DE_OBRA'
                  AND ativa = TRUE
                  AND (
                      (origem_id = ? AND destino_id = ?)
                      OR (origem_id = ? AND destino_id = ?)
                  )
                """,
                Integer.class,
                firstRdoId,
                firstWorkforceItemId,
                secondRdoId,
                secondWorkforceItemId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_relacao
                WHERE origem_tipo = 'RDO_MAO_OBRA'
                  AND origem_id IN (?, ?)
                  AND tipo_relacao = 'REFERENCIA_COLABORADOR'
                  AND ativa = TRUE
                """,
                Integer.class,
                firstWorkforceItemId,
                secondWorkforceItemId
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM cortex_relacao
                WHERE origem_tipo = 'RDO_MAO_OBRA'
                  AND origem_id = ?
                  AND destino_tipo = 'RDO_MAO_OBRA'
                  AND destino_id = ?
                  AND tipo_relacao = 'DERIVADO_DE'
                  AND ativa = TRUE
                """,
                Integer.class,
                secondWorkforceItemId,
                firstWorkforceItemId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM colaborador",
                Integer.class
        )).isEqualTo(collaboratorsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vinculo_colaborador_obra",
                Integer.class
        )).isEqualTo(linksBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_identity",
                Integer.class
        )).isEqualTo(identitiesBefore);
    }

    private static SyncService syncService(
            CurrentUserService currentUsers,
            FinancialAccessService financialAccess,
            CortexOperationalMemoryService memory
    ) {
        RdoOperationalDetailService details = new RdoOperationalDetailService(
                jdbc, memory
        );
        RdoAttachmentService attachments = new RdoAttachmentService(jdbc, mapper);
        RdoQueryService query = new RdoQueryService(jdbc, details, attachments);
        RdoService rdoService = new RdoService(
                jdbc,
                mapper,
                currentUsers,
                new RdoAssetEligibilityService(jdbc),
                new RdoMemoryPublisher(memory, jdbc),
                details,
                attachments,
                new RdoOperationalEventService(memory),
                mock(PrevisaoFinanceiraService.class),
                query,
                mock(ObraOperabilityGuard.class)
        );
        RdoSyncOperationHandler rdoHandler = new RdoSyncOperationHandler(
                jdbc,
                mapper,
                rdoService,
                mock(RdoDraftUpdateService.class),
                mock(RdoWorkflowService.class),
                query,
                currentUsers
        );
        return new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(rdoHandler)),
                currentUsers,
                financialAccess
        );
    }

    private static ObjectNode rdoPayload(
            String rdoId,
            String worksiteId,
            String previousRdoId,
            long contextVersion,
            String actorId,
            String workerId,
            String workforceItemId,
            String executionId,
            ServiceCatalogEntry service,
            ServicePriceVersion price
    ) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("apontadorColaboradorId", actorId);
        payload.put("creationContextVersion", contextVersion);
        payload.put("dataRdo", FLOW_DATE.toString());
        payload.put("id", rdoId);
        ArrayNode workforce = payload.putArray("maoObra");
        workforce.addObject()
                .put("cargo", "APONTADOR")
                .put("colaboradorId", actorId)
                .put("id", id())
                .put("nomeColaborador", "Operadora scoped")
                .put("tipoVinculo", "CONTRATADO");
        workforce.addObject()
                .put("cargo", "OPERACIONAL")
                .put("colaboradorId", workerId)
                .put("id", workforceItemId)
                .put("nomeColaborador", "Equipe atual")
                .put("tipoVinculo", "CONTRATADO");
        payload.put("obraId", worksiteId);
        payload.put("previousRdoId", previousRdoId);
        ArrayNode executions = payload.putArray("servicosExecutados");
        executions.addObject()
                .put("id", executionId)
                .put("priceVersionId", price.id())
                .put("producaoRejeitada", false)
                .put("quantidadeExecutada", new BigDecimal("2.000"))
                .put("retrabalho", false)
                .put("serviceId", service.id())
                .put("servicoNome", service.name())
                .put("statusValidacao", "VALIDADA")
                .put("turno", "DIURNO")
                .put("unidade", price.unit());
        payload.put("turno", "DIURNO");
        return payload;
    }

    private static ObjectNode manualWorkforcePayload(
            String rdoId,
            String worksiteId,
            String previousRdoId,
            long contextVersion,
            LocalDate date,
            String workforceItemId,
            String originItemId
    ) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("creationContextVersion", contextVersion);
        payload.put("dataRdo", date.toString());
        payload.put("id", rdoId);
        payload.put("obraId", worksiteId);
        if (previousRdoId == null) {
            payload.putNull("previousRdoId");
        } else {
            payload.put("previousRdoId", previousRdoId);
        }
        ObjectNode workforceItem = payload.putArray("maoObra").addObject();
        workforceItem.put("cargo", "SERVENTE");
        workforceItem.putNull("colaboradorId");
        workforceItem.put("id", workforceItemId);
        workforceItem.put("nomeColaborador", "Maria Servente");
        workforceItem.put("quantidade", BigDecimal.ONE);
        workforceItem.put("tipoVinculo", "CONTRATADO");
        if (originItemId == null) {
            workforceItem.putNull("origemItemId");
        } else {
            workforceItem.put("origemItemId", originItemId);
        }
        payload.put("turno", "DIURNO");
        return payload;
    }

    private static SyncPushRequest canonicalCreateRequest(
            String deviceId,
            String actorId,
            String worksiteId,
            String rdoId,
            ObjectNode payload
    ) throws Exception {
        String mutationId = id();
        String correlationId = id();
        List<String> changedFields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(changedFields::add);
        Collections.sort(changedFields);
        SyncPushRequest.MutacaoCliente mutation = new SyncPushRequest.MutacaoCliente(
                mutationId,
                "RDO",
                rdoId,
                "CRIAR_RDO",
                null,
                payload,
                LocalDateTime.of(2026, 7, 22, 11, 0),
                correlationId,
                13,
                deviceId,
                actorId,
                worksiteId,
                "RDO",
                rdoId,
                "CREATE",
                null,
                changedFields,
                "2026-07-22T11:00:00.000Z",
                new SyncPushRequest.MutationTrace(
                        actorId,
                        deviceId,
                        List.of(worksiteId),
                        correlationId,
                        null,
                        id(),
                        sha256(canonicalJson(payload))
                ),
                new SyncPushRequest.FieldPatch(
                        payload.deepCopy(), mapper.createObjectNode()
                ),
                List.of(),
                List.of()
        );
        return new SyncPushRequest(deviceId, List.of(mutation));
    }

    private static void assertApplied(SyncPushResponse response, String rdoId) {
        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.status())
                    .withFailMessage(
                            "Mutação Cortex 3 rejeitada: %s",
                            result.erro()
                    )
                    .isEqualTo("APLICADA");
            assertThat(result.entidadeTipo()).isEqualTo("RDO");
            assertThat(result.entidadeId()).isEqualTo(rdoId);
            assertThat(result.erro()).isNull();
        });
    }

    private static void projectGraph() {
        PostgresqlOntologyGraphRepository repository =
                new PostgresqlOntologyGraphRepository(
                        jdbc, mapper, transactionManager
                );
        new GraphProjectionCatchUpService(
                new PostgresqlCommittedOperationalEventReader(jdbc, mapper),
                new GraphProjectionService(
                        new OperationalGraphProjector(), repository
                ),
                repository
        ).projectAvailable(10_000);
    }

    private static void insertWorksite(Obra worksite) {
        jdbc.update("""
                INSERT INTO obra (
                    id, codigo_contrato, codigo_cw, codigo_interno, nome,
                    cliente, cidade, uf, rodovia, status, fonte_criacao
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, worksite.getId(), worksite.getCodigoContrato(),
                worksite.getCodigoCw(), worksite.getCodigoInterno(),
                worksite.getNome(), worksite.getCliente(), worksite.getCidade(),
                worksite.getUf(), worksite.getRodovia(), worksite.getStatus(),
                worksite.getFonteCriacao());
    }

    private static String insertForeignWorksite() {
        String worksiteId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                worksiteId,
                "CTR-FOREIGN-" + worksiteId,
                "Obra fora do escopo"
        );
        return worksiteId;
    }

    private static String insertCollaborator(String name, String role) {
        String collaboratorId = id();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    codigo_colaborador, nome, papel_acesso, ativo
                ) VALUES (?, 'cortex-3-flow-it', 'colaborador', ?, ?, ?, ?, TRUE)
                """, collaboratorId, collaboratorId,
                collaboratorId.substring(0, 8), name, role);
        return collaboratorId;
    }

    private static void linkToWorksite(
            String collaboratorId,
            String worksiteId,
            String worksiteRole
    ) {
        jdbc.update("""
                INSERT INTO vinculo_colaborador_obra (
                    id, obra_id, colaborador_id, status, papel_na_obra
                ) VALUES (?, ?, ?, 'ATIVO', ?)
                """, id(), worksiteId, collaboratorId, worksiteRole);
    }

    private static void insertPreviousRdo(String rdoId, String worksiteId) {
        jdbc.update("""
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, numero_sequencial,
                    data_rdo, status, criado_em, atualizado_em
                ) VALUES (?, ?, 'RDO-0001', 1, ?, 'ENVIADO', ?, ?)
                """, rdoId, worksiteId, FLOW_DATE.minusDays(1),
                LocalDateTime.of(2026, 7, 21, 18, 0),
                LocalDateTime.of(2026, 7, 21, 18, 0));
    }

    private static void insertPreviousWorkforce(
            String itemId,
            String rdoId,
            String collaboratorId
    ) {
        jdbc.update("""
                INSERT INTO rdo_mao_obra (
                    id, rdo_id, colaborador_id, nome_colaborador,
                    cargo, tipo_vinculo, quantidade
                ) VALUES (?, ?, ?, 'Equipe anterior', 'OPERACIONAL',
                          'CONTRATADO', 1)
                """, itemId, rdoId, collaboratorId);
    }

    private static void insertContractItem(String worksiteId, String serviceCode) {
        jdbc.update("""
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total, status, vigencia_inicio
                ) VALUES (?, ?, 'CTR-CORTEX-3', ?, 'Pavimento contratado',
                          'M2', 10.000, 125.0000, 1250.00, 'ATIVO', ?)
                """, id(), worksiteId, serviceCode, FLOW_DATE.minusDays(30));
    }

    private static void authenticate(String actorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, actorId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static <T> T inTransaction(Supplier<T> operation) {
        return transactions.execute(status -> operation.get());
    }

    private static String canonicalJson(JsonNode value) throws Exception {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : value) {
                items.add(canonicalJson(item));
            }
            return "[" + String.join(",", items) + "]";
        }
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            List<String> entries = new ArrayList<>();
            for (String name : names) {
                entries.add(
                        mapper.writeValueAsString(name)
                                + ":" + canonicalJson(value.get(name))
                );
            }
            return "{" + String.join(",", entries) + "}";
        }
        if (value.isNumber()) {
            return canonicalNumber(value.decimalValue());
        }
        return mapper.writeValueAsString(value);
    }

    private static String canonicalNumber(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return "0";
        }
        int exponent = normalized.precision() - normalized.scale() - 1;
        if (exponent > -7 && exponent < 21) {
            return normalized.toPlainString();
        }
        String digits = normalized.unscaledValue().abs().toString();
        String fraction = digits.length() == 1
                ? digits
                : digits.charAt(0) + "." + digits.substring(1);
        String sign = normalized.signum() < 0 ? "-" : "";
        String exponentSign = exponent >= 0 ? "+" : "";
        return sign + fraction + "e" + exponentSign + exponent;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}

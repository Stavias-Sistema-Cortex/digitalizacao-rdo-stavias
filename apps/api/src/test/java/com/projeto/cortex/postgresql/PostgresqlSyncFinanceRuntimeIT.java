package com.projeto.cortex.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceService;
import com.projeto.cortex.financeiro.invoice.FinanceLedgerService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlSyncFinanceRuntimeIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_runtime_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    @AfterEach
    void clearAuthentication() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void persistsAndReplaysAppliedMutationThenReopensErroForRetry() {
        String ownerId = collaborator();
        String deviceId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, usuario_id, ativo) VALUES (?, ?, TRUE)",
                deviceId,
                ownerId
        );
        jdbc.update(
                "INSERT INTO sync_estado_dispositivo (dispositivo_id) VALUES (?)",
                deviceId
        );

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        AtomicBoolean retryObservedPending = new AtomicBoolean();
        SyncOperationHandler handler = syncHandler(
                memory,
                deviceId,
                attempts,
                retryObservedPending
        );
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(ownerId);
        SyncService service = new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );

        String appliedEntityId = UUID.randomUUID().toString();
        SyncPushRequest appliedRequest = syncRequest(
                deviceId,
                "pg-sync-applied-" + UUID.randomUUID(),
                appliedEntityId,
                "applied"
        );
        SyncPushResponse first = service.push(appliedRequest);
        SyncPushResponse replay = service.push(appliedRequest);

        assertThat(first.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(replay.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(attempts.get(appliedRequest.mutacoes().getFirst().clientMutationId()))
                .hasValue(1);
        assertThat(jdbc.queryForMap(
                """
                SELECT status, jsonb_typeof(payload_json) AS payload_type,
                       jsonb_typeof(resultado_json) AS result_type,
                       resultado_json ->> 'id' AS result_id
                FROM sync_mutacao_cliente
                WHERE dispositivo_id = ? AND client_mutation_id = ?
                """,
                deviceId,
                appliedRequest.mutacoes().getFirst().clientMutationId()
        )).containsEntry("status", "APLICADA")
                .containsEntry("payload_type", "object")
                .containsEntry("result_type", "object")
                .containsEntry("result_id", appliedEntityId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_mutacao_cliente WHERE dispositivo_id = ? AND client_mutation_id = ?",
                Integer.class,
                deviceId,
                appliedRequest.mutacoes().getFirst().clientMutationId()
        )).isEqualTo(1);

        String retryEntityId = UUID.randomUUID().toString();
        SyncPushRequest retryRequest = syncRequest(
                deviceId,
                "pg-sync-retry-" + UUID.randomUUID(),
                retryEntityId,
                "fail-once"
        );
        SyncPushResponse failed = service.push(retryRequest);
        assertThat(failed.resultados().getFirst().status()).isEqualTo("ERRO");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sync_mutacao_cliente WHERE dispositivo_id = ? AND client_mutation_id = ?",
                String.class,
                deviceId,
                retryRequest.mutacoes().getFirst().clientMutationId()
        )).isEqualTo("ERRO");

        SyncPushResponse retried = service.push(retryRequest);
        SyncPushResponse retryReplay = service.push(retryRequest);

        assertThat(retried.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(retryReplay.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(retryObservedPending).isTrue();
        assertThat(attempts.get(retryRequest.mutacoes().getFirst().clientMutationId()))
                .hasValue(2);
        assertThat(jdbc.queryForMap(
                """
                SELECT status, payload_json ->> 'value' AS payload_value,
                       resultado_json ->> 'id' AS result_id
                FROM sync_mutacao_cliente
                WHERE dispositivo_id = ? AND client_mutation_id = ?
                """,
                deviceId,
                retryRequest.mutacoes().getFirst().clientMutationId()
        )).containsEntry("status", "APLICADA")
                .containsEntry("payload_value", "fail-once")
                .containsEntry("result_id", retryEntityId);
    }

    @Test
    void commitsInvoiceLedgerAndSettlementWithReplaySafeJsonbAudit() {
        String actorId = collaborator();
        String worksiteId = worksite();
        String supplierId = supplier(actorId, worksiteId);
        String costCenterId = costCenter(actorId, worksiteId);
        String categoryId = category(actorId, worksiteId);
        String invoiceStatusId = financeStatus(
                actorId,
                worksiteId,
                "NOTA_FISCAL",
                "RECEBIDA"
        );
        String ledgerStatusId = financeStatus(
                actorId,
                worksiteId,
                "LANCAMENTO",
                "ABERTO"
        );
        authenticate(actorId);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        FinanceOntologyProjector projector = new FinanceOntologyProjector(memory);
        CurrentUserService currentUser = new CurrentUserService(
                jdbc,
                new MockEnvironment(),
                false
        );
        FinanceInvoiceService invoiceService = new FinanceInvoiceService(
                jdbc,
                currentUser,
                projector,
                mapper
        );
        FinanceLedgerService ledgerService = new FinanceLedgerService(
                jdbc,
                currentUser,
                projector,
                mapper
        );
        FinanceAuditContext audit = FinanceAuditContext.online(
                actorId,
                "postgresql-jsonb-runtime"
        );

        String invoiceId = UUID.randomUUID().toString();
        FinanceInvoiceDtos.InvoiceRequest invoiceRequest = invoiceRequest(
                invoiceId,
                "pg-invoice-" + UUID.randomUUID(),
                worksiteId,
                supplierId,
                costCenterId,
                categoryId,
                actorId,
                invoiceStatusId
        );
        FinanceInvoiceDtos.InvoiceResponse invoice = transactions.execute(
                status -> invoiceService.save(invoiceRequest, audit)
        );
        Map<String, Object> invoiceAudit = jdbc.queryForMap(
                """
                SELECT id, estado_novo_json::text AS state
                FROM finance_nota_fiscal_historico
                WHERE nota_fiscal_id = ?
                """,
                invoiceId
        );
        FinanceInvoiceDtos.InvoiceResponse invoiceReplay = transactions.execute(
                status -> invoiceService.save(invoiceRequest, audit)
        );
        assertThat(invoiceReplay.id()).isEqualTo(invoice.id());
        assertThat(jdbc.queryForMap(
                """
                SELECT id, estado_novo_json::text AS state
                FROM finance_nota_fiscal_historico
                WHERE nota_fiscal_id = ?
                """,
                invoiceId
        )).isEqualTo(invoiceAudit);
        assertJsonbAudit("finance_nota_fiscal_historico", "nota_fiscal_id", invoiceId);
        assertStatusJsonbAudit("NOTA_FISCAL", invoiceId);

        String ledgerId = UUID.randomUUID().toString();
        FinanceInvoiceDtos.LedgerRequest ledgerRequest = ledgerRequest(
                ledgerId,
                "pg-ledger-" + UUID.randomUUID(),
                worksiteId,
                supplierId,
                costCenterId,
                categoryId,
                actorId,
                ledgerStatusId
        );
        FinanceInvoiceDtos.LedgerResponse ledger = transactions.execute(
                status -> ledgerService.saveLedger(ledgerRequest, audit)
        );
        Map<String, Object> ledgerAudit = jdbc.queryForMap(
                """
                SELECT id, estado_novo_json::text AS state
                FROM finance_lancamento_historico
                WHERE lancamento_id = ?
                """,
                ledgerId
        );
        FinanceInvoiceDtos.LedgerResponse ledgerReplay = transactions.execute(
                status -> ledgerService.saveLedger(ledgerRequest, audit)
        );
        assertThat(ledgerReplay.id()).isEqualTo(ledger.id());
        assertThat(jdbc.queryForMap(
                """
                SELECT id, estado_novo_json::text AS state
                FROM finance_lancamento_historico
                WHERE lancamento_id = ?
                """,
                ledgerId
        )).isEqualTo(ledgerAudit);
        assertJsonbAudit("finance_lancamento_historico", "lancamento_id", ledgerId);
        assertStatusJsonbAudit("LANCAMENTO", ledgerId);

        String settlementId = UUID.randomUUID().toString();
        FinanceInvoiceDtos.SettlementRequest settlementRequest =
                new FinanceInvoiceDtos.SettlementRequest(
                        settlementId,
                        "pg-settlement-" + UUID.randomUUID(),
                        worksiteId,
                        "PAGAMENTO",
                        "EFETIVADA",
                        LocalDate.now(),
                        "BRL",
                        new BigDecimal("400.0000"),
                        "PIX",
                        "E2E-postgresql",
                        "Liquidação PostgreSQL",
                        List.of(new FinanceInvoiceDtos.SettlementAllocationRequest(
                                null,
                                ledgerId,
                                new BigDecimal("400.0000")
                        )),
                        null
                );
        FinanceInvoiceDtos.SettlementResponse settlement = transactions.execute(
                status -> ledgerService.saveSettlement(settlementRequest, audit)
        );
        Map<String, Object> settlementAudit = jdbc.queryForMap(
                """
                SELECT id, estado_novo_json::text AS state
                FROM finance_liquidacao_historico
                WHERE liquidacao_id = ?
                """,
                settlementId
        );
        FinanceInvoiceDtos.SettlementResponse settlementReplay =
                transactions.execute(
                        status -> ledgerService.saveSettlement(
                                settlementRequest,
                                audit
                        )
                );
        assertThat(settlementReplay.id()).isEqualTo(settlement.id());
        assertThat(jdbc.queryForMap(
                """
                SELECT id, estado_novo_json::text AS state
                FROM finance_liquidacao_historico
                WHERE liquidacao_id = ?
                """,
                settlementId
        )).isEqualTo(settlementAudit);
        assertJsonbAudit(
                "finance_liquidacao_historico",
                "liquidacao_id",
                settlementId
        );
        assertThat(ledgerService.getLedger(ledgerId).valorLiquidado())
                .isEqualByComparingTo("400.0000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_liquidacao_lancamento WHERE liquidacao_id = ?",
                Integer.class,
                settlementId
        )).isEqualTo(1);
    }

    private SyncOperationHandler syncHandler(
            CortexOperationalMemoryService memory,
            String deviceId,
            Map<String, AtomicInteger> attempts,
            AtomicBoolean retryObservedPending
    ) {
        return new SyncOperationHandler() {
            @Override
            public String entityType() {
                return "TESTE";
            }

            @Override
            public Set<String> operations() {
                return Set.of("CRIAR_TESTE");
            }

            @Override
            public boolean requiresBaseVersion(String operation) {
                return false;
            }

            @Override
            public AppliedSyncMutation apply(
                    SyncPushRequest.MutacaoCliente mutation,
                    SyncMutationContext context
            ) {
                int attempt = attempts.computeIfAbsent(
                        mutation.clientMutationId(),
                        ignored -> new AtomicInteger()
                ).incrementAndGet();
                if ("fail-once".equals(mutation.payload().path("value").asText())
                        && attempt == 1) {
                    throw new IllegalStateException("falha transitória de teste");
                }
                if ("fail-once".equals(mutation.payload().path("value").asText())) {
                    retryObservedPending.set("PENDENTE".equals(jdbc.queryForObject(
                            "SELECT status FROM sync_mutacao_cliente WHERE dispositivo_id = ? AND client_mutation_id = ?",
                            String.class,
                            deviceId,
                            mutation.clientMutationId()
                    )));
                }
                memory.registrarEvento(
                        "TESTE",
                        mutation.entidadeId(),
                        "TESTE_CRIADO",
                        "POSTGRESQL_IT",
                        Map.of("value", mutation.payload().path("value").asText())
                );
                ObjectNode result = new ObjectMapper().createObjectNode();
                result.put("id", mutation.entidadeId());
                return new AppliedSyncMutation(
                        "TESTE",
                        mutation.entidadeId(),
                        result
                );
            }
        };
    }

    private SyncPushRequest syncRequest(
            String deviceId,
            String mutationId,
            String entityId,
            String value
    ) {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("value", value);
        return new SyncPushRequest(
                deviceId,
                List.of(new SyncPushRequest.MutacaoCliente(
                        mutationId,
                        "TESTE",
                        entityId,
                        "CRIAR_TESTE",
                        null,
                        payload,
                        LocalDateTime.of(2026, 7, 22, 12, 0),
                        "postgresql-runtime-it"
                ))
        );
    }

    private FinanceInvoiceDtos.InvoiceRequest invoiceRequest(
            String id,
            String mutationId,
            String worksiteId,
            String supplierId,
            String costCenterId,
            String categoryId,
            String actorId,
            String statusId
    ) {
        return new FinanceInvoiceDtos.InvoiceRequest(
                id,
                mutationId,
                worksiteId,
                supplierId,
                costCenterId,
                categoryId,
                actorId,
                statusId,
                "NF-PG-" + id,
                "1",
                null,
                "NFE",
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now().plusDays(10),
                "BRL",
                new BigDecimal("1000.0000"),
                new BigDecimal("50.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("940.0000"),
                "Documento fiscal PostgreSQL",
                List.of(),
                null
        );
    }

    private FinanceInvoiceDtos.LedgerRequest ledgerRequest(
            String id,
            String mutationId,
            String worksiteId,
            String supplierId,
            String costCenterId,
            String categoryId,
            String actorId,
            String statusId
    ) {
        return new FinanceInvoiceDtos.LedgerRequest(
                id,
                mutationId,
                worksiteId,
                null,
                supplierId,
                costCenterId,
                categoryId,
                actorId,
                statusId,
                "PAGAR",
                "MANUAL",
                "DOC-PG-" + id,
                "Serviço executado no PostgreSQL",
                LocalDate.now().minusDays(10),
                LocalDate.now().minusDays(10),
                LocalDate.now().minusDays(1),
                "BRL",
                new BigDecimal("940.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("940.0000"),
                List.of(new FinanceInvoiceDtos.AllocationRequest(
                        null,
                        costCenterId,
                        categoryId,
                        new BigDecimal("940.0000"),
                        new BigDecimal("100.000000")
                )),
                List.of(),
                null
        );
    }

    private void assertJsonbAudit(
            String table,
            String entityColumn,
            String entityId
    ) {
        assertThat(jdbc.queryForMap(
                "SELECT COUNT(*) AS total, MIN(jsonb_typeof(estado_novo_json)) AS json_type "
                        + "FROM " + table + " WHERE " + entityColumn + " = ?",
                entityId
        )).containsEntry("total", 1L)
                .containsEntry("json_type", "object");
    }

    private void assertStatusJsonbAudit(String entityType, String entityId) {
        assertThat(jdbc.queryForMap(
                """
                SELECT COUNT(*) AS total,
                       MIN(jsonb_typeof(estado_novo_json)) AS json_type
                FROM finance_status_historico
                WHERE entidade_tipo = ? AND entidade_id = ?
                """,
                entityType,
                entityId
        )).containsEntry("total", 1L)
                .containsEntry("json_type", "object");
    }

    private String collaborator() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'postgresql-it', 'colaborador', ?,
                          'Gestora PostgreSQL', 'ALFA', TRUE)
                """,
                id,
                id
        );
        return id;
    }

    private String worksite() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra PostgreSQL')",
                id,
                "PG-" + id
        );
        return id;
    }

    private String supplier(String actorId, String worksiteId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_fornecedor (id, razao_social, cnpj_normalizado, criado_por) VALUES (?, 'Fornecedor PostgreSQL', ?, ?)",
                id,
                String.format("%014d", Math.abs(id.hashCode())),
                actorId
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor_obra (id, fornecedor_id, obra_id, criado_por) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                id,
                worksiteId,
                actorId
        );
        return id;
    }

    private String costCenter(String actorId, String worksiteId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_centro_custo (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Centro PostgreSQL', ?)",
                id,
                worksiteId,
                "CC-" + id,
                actorId
        );
        return id;
    }

    private String category(String actorId, String worksiteId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_categoria (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Categoria PostgreSQL', ?)",
                id,
                worksiteId,
                "CAT-" + id,
                actorId
        );
        return id;
    }

    private String financeStatus(
            String actorId,
            String worksiteId,
            String aggregateType,
            String code
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome, ordem, criado_por
                ) VALUES (?, ?, ?, ?, ?, 1, ?)
                """,
                id,
                worksiteId,
                aggregateType,
                code,
                code,
                actorId
        );
        return id;
    }

    private void authenticate(String actorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, actorId);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }
}

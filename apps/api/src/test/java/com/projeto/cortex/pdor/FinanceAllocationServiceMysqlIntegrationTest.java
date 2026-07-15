package com.projeto.cortex.pdor;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialGrantRepository;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinanceAllocationServiceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void keepsAllocationExactIdempotentVersionedAuditedAndAtomic()
            throws Exception {
        database = PdorMysqlTestDatabase.create("finance_allocation");
        database.migrate();
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        Seed seed = seed(jdbc);
        authenticate(seed.actorId());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CurrentUserService currentUser = new CurrentUserService(
                jdbc,
                new MockEnvironment(),
                false
        );
        FinancialAccessService access = new FinancialAccessService(
                currentUser,
                new FinancialGrantRepository(jdbc),
                new FinancialUnitRepository(jdbc)
        );
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        FinanceOntologyProjector ontology = new FinanceOntologyProjector(memory);
        FinanceAllocationRepository repository =
                new FinanceAllocationRepository(jdbc);
        FinanceAllocationService service = new FinanceAllocationService(
                repository,
                access,
                ontology,
                mapper
        );
        FinanceAuditContext audit = new FinanceAuditContext(
                seed.actorId(),
                "device-real-1",
                "allocation-real-1",
                "ONLINE"
        );

        SaveAllocationRequest create = request(
                "allocation-mutation-1",
                0L,
                seed.purchaseId(),
                seed.worksiteUnitId(),
                "40.0000",
                seed.adminUnitId(),
                "60.0000"
        );
        AllocationResponse created = transactions.execute(
                status -> service.save(create, audit)
        );
        AllocationResponse replay = transactions.execute(
                status -> service.save(create, audit)
        );

        assertThat(replay).isEqualTo(created);
        assertThat(created.valorTotal()).isEqualByComparingTo("100.0000");
        assertThat(created.itens())
                .extracting(AllocationItemResponse::percentual)
                .containsExactly(
                        new BigDecimal("0.400000"),
                        new BigDecimal("0.600000")
                );
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_rateio_historico WHERE rateio_id = ?",
                Long.class,
                created.id()
        )).isEqualTo(1L);
        Map<String, Object> history = jdbc.queryForMap(
                """
                SELECT alterado_por, client_mutation_id,
                       correlacao_id, dispositivo_id,
                       estado_anterior_json, estado_novo_json
                FROM finance_rateio_historico
                WHERE rateio_id = ?
                """,
                created.id()
        );
        assertThat(history.get("alterado_por")).isEqualTo(seed.actorId());
        assertThat(history.get("client_mutation_id"))
                .isEqualTo("allocation-mutation-1");
        assertThat(history.get("correlacao_id"))
                .isEqualTo("allocation-mutation-1");
        assertThat(history.get("dispositivo_id"))
                .isEqualTo("device-real-1");
        assertThat(history.get("estado_anterior_json")).isNull();
        assertThat(String.valueOf(history.get("estado_novo_json")))
                .contains(seed.actorId(), seed.worksiteUnitId(), seed.adminUnitId());
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_relacao
                WHERE origem_tipo = 'RATEIO_FINANCEIRO'
                  AND origem_id = ?
                  AND destino_tipo = 'UNIDADE_FINANCEIRA'
                  AND tipo_relacao = 'RATEADO_PARA'
                  AND ativa = 1
                """,
                Long.class,
                created.id()
        )).isEqualTo(2L);

        AllocationResponse invoiceAllocation = transactions.execute(
                status -> service.save(
                        singleSourceRequest(
                                AllocationSourceType.NOTA_FISCAL,
                                seed.invoiceId(),
                                seed.worksiteUnitId(),
                                "75.0000",
                                "allocation-invoice"
                        ),
                        audit
                )
        );
        AllocationResponse ledgerAllocation = transactions.execute(
                status -> service.save(
                        singleSourceRequest(
                                AllocationSourceType.LANCAMENTO,
                                seed.ledgerId(),
                                seed.worksiteUnitId(),
                                "80.0000",
                                "allocation-ledger"
                        ),
                        audit
                )
        );
        assertThat(invoiceAllocation.valorTotal())
                .isEqualByComparingTo("75.0000");
        assertThat(invoiceAllocation.origemTipo())
                .isEqualTo(AllocationSourceType.NOTA_FISCAL);
        assertThat(ledgerAllocation.valorTotal())
                .isEqualByComparingTo("80.0000");
        assertThat(ledgerAllocation.origemTipo())
                .isEqualTo(AllocationSourceType.LANCAMENTO);

        assertThatThrownBy(() -> transactions.execute(status -> service.save(
                request(
                        "allocation-corporate-rejected",
                        0L,
                        seed.purchaseId(),
                        seed.corporateUnitId(),
                        "100.0000",
                        null,
                        null
                ),
                audit
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("corporativa");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_rateio_historico WHERE rateio_id = ?",
                Long.class,
                created.id()
        )).isEqualTo(1L);

        assertThatThrownBy(() -> transactions.execute(status -> service.save(
                request(
                        "allocation-stale",
                        9L,
                        seed.purchaseId(),
                        seed.worksiteUnitId(),
                        "50.0000",
                        seed.adminUnitId(),
                        "50.0000"
                ),
                audit
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("desatualizada");

        AllocationResponse updated = transactions.execute(status -> service.save(
                request(
                        "allocation-mutation-2",
                        0L,
                        seed.purchaseId(),
                        seed.worksiteUnitId(),
                        "30.0000",
                        seed.adminUnitId(),
                        "70.0000"
                ),
                audit
        ));
        assertThat(updated.versao()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_rateio_historico WHERE rateio_id = ?",
                Long.class,
                created.id()
        )).isEqualTo(2L);

        proveConcurrentVersionConflict(
                dataSource,
                repository,
                mapper,
                seed,
                created.id()
        );
        proveConcurrentIdenticalMutation(
                dataSource,
                repository,
                mapper,
                ontology,
                seed,
                created.id()
        );
        proveRollbackWhenOntologyFails(
                jdbc,
                transactions,
                repository,
                mapper,
                seed,
                audit
        );
    }

    private void proveConcurrentIdenticalMutation(
            DataSource dataSource,
            FinanceAllocationRepository repository,
            ObjectMapper mapper,
            FinanceOntologyProjector ontology,
            Seed seed,
            String allocationId
    ) throws Exception {
        FinanceAllocationService concurrentService = new FinanceAllocationService(
                repository,
                mock(FinancialAccessService.class),
                ontology,
                mapper
        );
        SaveAllocationRequest request = request(
                "allocation-concurrent-same",
                2L,
                seed.purchaseId(),
                seed.worksiteUnitId(),
                "100.0000",
                null,
                null
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AllocationResponse> first = executor.submit(() -> {
                start.await();
                return new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                ).execute(status -> concurrentService.save(
                        request,
                        FinanceAuditContext.online(
                                seed.actorId(),
                                "allocation-concurrent-same"
                        )
                ));
            });
            Future<AllocationResponse> second = executor.submit(() -> {
                start.await();
                return new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                ).execute(status -> concurrentService.save(
                        request,
                        FinanceAuditContext.online(
                                seed.actorId(),
                                "allocation-concurrent-same"
                        )
                ));
            });
            start.countDown();
            AllocationResponse firstResponse = first.get();
            AllocationResponse secondResponse = second.get();
            assertThat(firstResponse).isEqualTo(secondResponse);
            assertThat(firstResponse.versao()).isEqualTo(3L);
        } finally {
            executor.shutdownNow();
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_rateio_mutacao
                WHERE ator_id = ?
                  AND client_mutation_id = 'allocation-concurrent-same'
                """,
                Long.class,
                seed.actorId()
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_rateio_historico WHERE rateio_id = ?",
                Long.class,
                allocationId
        )).isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_relacao
                WHERE origem_tipo = 'RATEIO_FINANCEIRO'
                  AND origem_id = ?
                  AND tipo_relacao = 'RATEADO_PARA'
                  AND ativa = 1
                """,
                Long.class,
                allocationId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_relacao
                WHERE origem_tipo = 'RATEIO_FINANCEIRO'
                  AND origem_id = ?
                  AND tipo_relacao = 'RATEADO_PARA'
                  AND ativa = 0
                """,
                Long.class,
                allocationId
        )).isEqualTo(1L);
    }

    private void proveConcurrentVersionConflict(
            DataSource dataSource,
            FinanceAllocationRepository repository,
            ObjectMapper mapper,
            Seed seed,
            String allocationId
    ) throws Exception {
        FinancialAccessService access = mock(FinancialAccessService.class);
        FinanceOntologyProjector ontology = mock(FinanceOntologyProjector.class);
        FinanceAllocationService concurrentService = new FinanceAllocationService(
                repository,
                access,
                ontology,
                mapper
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> concurrentUpdate(
                    dataSource,
                    concurrentService,
                    seed,
                    start,
                    "allocation-concurrent-a",
                    "45.0000",
                    "55.0000"
            ));
            Future<String> second = executor.submit(() -> concurrentUpdate(
                    dataSource,
                    concurrentService,
                    seed,
                    start,
                    "allocation-concurrent-b",
                    "55.0000",
                    "45.0000"
            ));
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("OK", "CONFLICT");
        } finally {
            executor.shutdownNow();
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM finance_rateio WHERE id = ?",
                Long.class,
                allocationId
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_rateio_historico WHERE rateio_id = ?",
                Long.class,
                allocationId
        )).isEqualTo(3L);
    }

    private String concurrentUpdate(
            DataSource dataSource,
            FinanceAllocationService service,
            Seed seed,
            CountDownLatch start,
            String mutation,
            String first,
            String second
    ) throws Exception {
        start.await();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        try {
            transaction.execute(status -> service.save(
                    request(
                            mutation,
                            1L,
                            seed.purchaseId(),
                            seed.worksiteUnitId(),
                            first,
                            seed.adminUnitId(),
                            second
                    ),
                    FinanceAuditContext.online(seed.actorId(), mutation)
            ));
            return "OK";
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value() == 409
                    ? "CONFLICT"
                    : "HTTP_" + exception.getStatusCode().value();
        }
    }

    private void proveRollbackWhenOntologyFails(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            FinanceAllocationRepository repository,
            ObjectMapper mapper,
            Seed seed,
            FinanceAuditContext audit
    ) {
        FinanceOntologyProjector failingOntology =
                mock(FinanceOntologyProjector.class);
        doThrow(new IllegalStateException("ontology unavailable"))
                .when(failingOntology)
                .successWithRelations(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()
                );
        FinanceAllocationService failingService = new FinanceAllocationService(
                repository,
                mock(FinancialAccessService.class),
                failingOntology,
                mapper
        );

        assertThatThrownBy(() -> transactions.execute(status -> failingService.save(
                request(
                        "allocation-rollback",
                        0L,
                        seed.secondPurchaseId(),
                        seed.worksiteUnitId(),
                        "100.0000",
                        null,
                        null
                ),
                audit
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ontology unavailable");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_rateio WHERE compra_id = ?",
                Long.class,
                seed.secondPurchaseId()
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_rateio_historico
                WHERE client_mutation_id = 'allocation-rollback'
                """,
                Long.class
        )).isZero();
    }

    private SaveAllocationRequest request(
            String mutation,
            long baseVersion,
            String purchaseId,
            String firstUnit,
            String firstValue,
            String secondUnit,
            String secondValue
    ) {
        List<AllocationItemRequest> items = secondUnit == null
                ? List.of(item(firstUnit, firstValue))
                : List.of(
                        item(firstUnit, firstValue),
                        item(secondUnit, secondValue)
                );
        return new SaveAllocationRequest(
                mutation,
                baseVersion,
                AllocationSourceType.COMPRA,
                purchaseId,
                items,
                mutation,
                "device-real-1"
        );
    }

    private SaveAllocationRequest singleSourceRequest(
            AllocationSourceType type,
            String sourceId,
            String unitId,
            String value,
            String mutation
    ) {
        return new SaveAllocationRequest(
                mutation,
                0L,
                type,
                sourceId,
                List.of(item(unitId, value)),
                mutation,
                "device-real-1"
        );
    }

    private AllocationItemRequest item(String unitId, String value) {
        return new AllocationItemRequest(
                unitId,
                null,
                null,
                new BigDecimal(value)
        );
    }

    private Seed seed(JdbcTemplate jdbc) {
        String actorId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, papel_acesso, ativo
                ) VALUES (?, 'teste', 'colaborador', ?,
                          'Alfa Financeira', 'ALFA', 1)
                """,
                actorId,
                actorId
        );
        String worksiteId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                worksiteId,
                "RATEIO-" + worksiteId,
                "Obra do Rateio"
        );
        String worksiteUnitId = unit(
                jdbc, actorId, "OBRA", worksiteId, "OBRA:" + worksiteId
        );
        String adminUnitId = unit(
                jdbc, actorId, "ADMINISTRATIVO", null, "ADM:SEDE"
        );
        String corporateUnitId = unit(
                jdbc, actorId, "CORPORATIVO", null, "CORP:GLOBAL"
        );
        String supplierId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_fornecedor (
                    id, razao_social, cnpj_normalizado, criado_por
                ) VALUES (?, 'Fornecedor Rateio', ?, ?)
                """,
                supplierId,
                String.format("%014d", 1),
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_fornecedor_obra (
                    id, fornecedor_id, obra_id, criado_por
                ) VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                supplierId,
                worksiteId,
                actorId
        );
        String costCenterId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_centro_custo (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'RATEIO', 'Centro Rateio', ?)
                """,
                costCenterId,
                worksiteId,
                actorId
        );
        String categoryId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_categoria (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'RATEIO', 'Categoria Rateio', ?)
                """,
                categoryId,
                worksiteId,
                actorId
        );
        String statusId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome,
                    ordem, criado_por
                ) VALUES (?, ?, 'COMPRA', 'ATIVA', 'Ativa', 1, ?)
                """,
                statusId,
                worksiteId,
                actorId
        );
        String invoiceStatusId = status(
                jdbc,
                actorId,
                worksiteId,
                "NOTA_FISCAL"
        );
        String ledgerStatusId = status(
                jdbc,
                actorId,
                worksiteId,
                "LANCAMENTO"
        );
        String purchaseId = purchase(
                jdbc, actorId, worksiteId, costCenterId, categoryId,
                supplierId, statusId, "purchase-allocation-1"
        );
        String secondPurchaseId = purchase(
                jdbc, actorId, worksiteId, costCenterId, categoryId,
                supplierId, statusId, "purchase-allocation-2"
        );
        String invoiceId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    responsavel_id, status_id, numero, serie,
                    tipo_documento, data_emissao, moeda,
                    valor_bruto, valor_liquido, criado_por
                ) VALUES (?, 'invoice-allocation-source', ?, ?, ?, ?,
                          'NF-RATEIO-1', '', 'NFE', CURRENT_DATE,
                          'BRL', 75.0000, 75.0000, ?)
                """,
                invoiceId,
                worksiteId,
                supplierId,
                actorId,
                invoiceStatusId,
                actorId
        );
        String ledgerId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_lancamento (
                    id, client_mutation_id, obra_id, responsavel_id,
                    status_id, tipo, origem, descricao, data_competencia,
                    vencimento_em, moeda, valor_original, valor_liquido,
                    criado_por
                ) VALUES (?, 'ledger-allocation-source', ?, ?, ?,
                          'PAGAR', 'MANUAL', 'Lançamento Rateável',
                          CURRENT_DATE, CURRENT_DATE, 'BRL',
                          80.0000, 80.0000, ?)
                """,
                ledgerId,
                worksiteId,
                actorId,
                ledgerStatusId,
                actorId
        );
        return new Seed(
                actorId,
                purchaseId,
                secondPurchaseId,
                invoiceId,
                ledgerId,
                worksiteUnitId,
                adminUnitId,
                corporateUnitId
        );
    }

    private String status(
            JdbcTemplate jdbc,
            String actorId,
            String worksiteId,
            String aggregateType
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome,
                    ordem, criado_por
                ) VALUES (?, ?, ?, 'ATIVA', 'Ativa', 1, ?)
                """,
                id,
                worksiteId,
                aggregateType,
                actorId
        );
        return id;
    }

    private String unit(
            JdbcTemplate jdbc,
            String actorId,
            String type,
            String worksiteId,
            String code
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, obra_id, codigo, nome, status, origem,
                    criado_por, atualizado_por
                ) VALUES (?, ?, ?, ?, ?, 'ATIVA', 'MANUAL', ?, ?)
                """,
                id,
                type,
                worksiteId,
                code,
                code,
                actorId,
                actorId
        );
        return id;
    }

    private String purchase(
            JdbcTemplate jdbc,
            String actorId,
            String worksiteId,
            String costCenterId,
            String categoryId,
            String supplierId,
            String statusId,
            String mutation
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, fornecedor_id, responsavel_compra_id,
                    status_id, descricao, prioridade, moeda,
                    valor_contratado, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Compra Rateável',
                          'MEDIA', 'BRL', 100.0000, ?)
                """,
                id,
                mutation,
                worksiteId,
                costCenterId,
                categoryId,
                supplierId,
                actorId,
                statusId,
                actorId
        );
        return id;
    }

    private void authenticate(String actorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                actorId
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }

    private record Seed(
            String actorId,
            String purchaseId,
            String secondPurchaseId,
            String invoiceId,
            String ledgerId,
            String worksiteUnitId,
            String adminUnitId,
            String corporateUnitId
    ) {
    }
}

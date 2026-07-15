package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceCatalogService;
import com.projeto.cortex.financeiro.core.FinanceDtos;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinancePurchaseService;
import com.projeto.cortex.financeiro.core.sync.FinancePurchaseSyncOperationHandler;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinancePurchaseServiceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void createsFiltersAndApprovesRealPurchaseFromConfiguredRule() {
        database = PdorMysqlTestDatabase.create("finance_service");
        database.migrate();
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        String actorId = collaborator(jdbc, "ALFA", "Gestora Financeira");
        String obraId = worksite(jdbc);
        authenticate(actorId);

        CurrentUserService currentUser = new CurrentUserService(
                jdbc, new MockEnvironment(), false
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc, mapper, org.mockito.Mockito.mock(
                        ApplicationEventPublisher.class
                )
        );
        FinanceOntologyProjector ontology = new FinanceOntologyProjector(memory);
        FinanceCatalogService catalog = new FinanceCatalogService(
                jdbc, currentUser, ontology
        );
        FinancePurchaseService purchases = new FinancePurchaseService(
                jdbc, currentUser, ontology, mapper
        );
        FinanceAuditContext audit = FinanceAuditContext.online(
                actorId, "finance-integration"
        );

        FinanceDtos.CostCenterResponse center = transactions.execute(status ->
                catalog.saveCostCenter(
                        obraId,
                        new FinanceDtos.CostCenterRequest(
                                null, "CC-PAV", "Pavimentação", null, actorId
                        ),
                        audit
                )
        );
        assertThatThrownBy(() -> transactions.execute(status ->
                catalog.saveCostCenter(
                        obraId,
                        new FinanceDtos.CostCenterRequest(
                                UUID.randomUUID().toString(),
                                "CC-PAV",
                                "Centro duplicado",
                                null,
                                actorId
                        ),
                        audit
                )
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("este código");
        FinanceDtos.CategoryResponse category = transactions.execute(status ->
                catalog.saveCategory(
                        obraId,
                        new FinanceDtos.CategoryRequest(
                                null, "MAT", "Materiais", null
                        ),
                        audit
                )
        );
        FinanceDtos.SupplierResponse supplier = transactions.execute(status ->
                catalog.saveSupplier(
                        obraId,
                        new FinanceDtos.SupplierRequest(
                                null,
                                "Fornecedor de Brita",
                                "Brita Real",
                                "12.345.678/0001-95",
                                "financeiro@fornecedor.example",
                                null
                        ),
                        audit
                )
        );
        FinanceDtos.StatusDefinitionResponse draft = transactions.execute(status ->
                catalog.saveStatus(
                        obraId,
                        new FinanceDtos.StatusDefinitionRequest(
                                null, "COMPRA", "RASCUNHO", "Rascunho",
                                null, 1, false
                        ),
                        audit
                )
        );
        FinanceDtos.StatusDefinitionResponse approved = transactions.execute(status ->
                catalog.saveStatus(
                        obraId,
                        new FinanceDtos.StatusDefinitionRequest(
                                null, "COMPRA", "APROVADA", "Aprovada",
                                null, 2, false
                        ),
                        audit
                )
        );
        transactions.executeWithoutResult(status -> catalog.saveTransition(
                obraId,
                new FinanceDtos.StatusTransitionRequest(
                        null, "COMPRA", draft.id(), approved.id(), true
                ),
                audit
        ));
        transactions.execute(status -> catalog.saveApprovalRule(
                obraId,
                new FinanceDtos.ApprovalRuleRequest(
                        null,
                        center.id(),
                        category.id(),
                        "Aprovação de materiais",
                        "ALTA",
                        "BRL",
                        new BigDecimal("1000.0000"),
                        new BigDecimal("5000.0000"),
                        LocalDateTime.now().minusDays(1),
                        null,
                        List.of(new FinanceDtos.ApprovalStepRequest(
                                null, 1, "COLABORADOR", actorId,
                                null, 1
                        ))
                ),
                audit
        ));

        String purchaseId = UUID.randomUUID().toString();
        FinanceDtos.PurchaseRequest request = new FinanceDtos.PurchaseRequest(
                purchaseId,
                "purchase-client-1",
                null,
                obraId,
                center.id(),
                category.id(),
                supplier.id(),
                actorId,
                draft.id(),
                "PED-2026-001",
                "Brita graduada",
                "ALTA",
                "BRL",
                new BigDecimal("1800.0000"),
                null,
                new BigDecimal("1750.0000"),
                null,
                LocalDate.now(),
                LocalDate.now().plusDays(5),
                List.of(new FinanceDtos.LineItemRequest(
                        null,
                        "Brita graduada",
                        new BigDecimal("10.0000"),
                        "m³",
                        new BigDecimal("175.0000"),
                        new BigDecimal("1750.0000"),
                        null
                )),
                null
        );
        FinanceDtos.PurchaseResponse created = transactions.execute(status ->
                purchases.savePurchase(request, audit)
        );
        assertThat(jdbc.queryForObject(
                """
                SELECT natureza FROM finance_compra_item
                WHERE compra_id = ? AND ordem = 0
                """,
                String.class,
                purchaseId
        )).isEqualTo("CONSUMO");
        FinanceDtos.PurchaseResponse replay = transactions.execute(status ->
                purchases.savePurchase(request, audit)
        );
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(purchases.listPurchases(new FinancePurchaseService.FinanceFilter(
                obraId, null, null, actorId, supplier.id(), draft.id(),
                category.id(), center.id(), "ALTA", "Brita", 20, 0
        ))).extracting(FinanceDtos.PurchaseResponse::id)
                .containsExactly(purchaseId);

        FinanceDtos.StatusChangeResponse waiting = transactions.execute(status ->
                purchases.changePurchaseStatus(
                        purchaseId,
                        new FinanceDtos.StatusChangeRequest(
                                approved.id(), "status-client-1", created.versao()
                        ),
                        audit
                )
        );
        assertThat(waiting.statusAlterado()).isFalse();
        assertThat(waiting.aprovacaoStatus()).isEqualTo("PENDENTE");

        FinanceDtos.ApprovalDecisionResponse decision = transactions.execute(status ->
                purchases.decidePurchase(
                        purchaseId,
                        new FinanceDtos.ApprovalDecisionRequest(
                                "APROVAR", "Conferido", "decision-client-1"
                        ),
                        audit
                )
        );
        FinanceDtos.ApprovalDecisionResponse replayedDecision =
                transactions.execute(status -> purchases.decidePurchase(
                        purchaseId,
                        new FinanceDtos.ApprovalDecisionRequest(
                                "APROVAR", "Conferido", "decision-client-1"
                        ),
                        audit
                ));
        assertThat(decision.statusAprovacao()).isEqualTo("APROVADA");
        assertThat(replayedDecision).isEqualTo(decision);
        assertThatThrownBy(() -> transactions.execute(status ->
                purchases.decidePurchase(
                        purchaseId,
                        new FinanceDtos.ApprovalDecisionRequest(
                                "REJEITAR", "Alterada", "decision-client-1"
                        ),
                        audit
                )
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("outro conteúdo");

        FinanceDtos.StatusChangeResponse changed = transactions.execute(status ->
                purchases.changePurchaseStatus(
                        purchaseId,
                        new FinanceDtos.StatusChangeRequest(
                                approved.id(), "status-client-1", created.versao()
                        ),
                        audit
                )
        );
        assertThat(changed.statusAlterado()).isTrue();
        assertThat(changed.statusCodigo()).isEqualTo("APROVADA");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_evento_operacional
                WHERE obra_id = ? AND usuario_id = ?
                  AND correlacao_id = ? AND fonte = 'CORTEX_FINANCEIRO'
                """,
                Integer.class,
                obraId,
                actorId,
                "finance-integration"
        )).isGreaterThan(0);

        long canonicalVersion = jdbc.queryForObject(
                "SELECT versao_entidade FROM cortex_estado_entidade WHERE tipo_entidade = 'COMPRA' AND entidade_id = ?",
                Long.class,
                purchaseId
        );
        long rowVersionBeforeSync = jdbc.queryForObject(
                "SELECT versao_linha FROM finance_compra WHERE id = ?",
                Long.class,
                purchaseId
        );
        assertThat(canonicalVersion).isNotEqualTo(rowVersionBeforeSync);

        String deviceId = UUID.randomUUID().toString();
        registerDevice(jdbc, actorId, deviceId);
        FinancialAccessService financialAccess = org.mockito.Mockito.mock(
                FinancialAccessService.class
        );
        FinancePurchaseSyncOperationHandler financeHandler =
                new FinancePurchaseSyncOperationHandler(
                        purchases, financialAccess, jdbc, mapper
                );
        SyncService sync = new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(financeHandler)),
                currentUser,
                financialAccess
        );
        FinanceDtos.PurchaseRequest offlineUpdate = new FinanceDtos.PurchaseRequest(
                purchaseId,
                "ignored-payload-mutation",
                null,
                obraId,
                center.id(),
                category.id(),
                supplier.id(),
                actorId,
                approved.id(),
                "PED-2026-001",
                "Brita graduada revisada offline",
                "ALTA",
                "BRL",
                new BigDecimal("1800.0000"),
                null,
                new BigDecimal("1750.0000"),
                null,
                LocalDate.now(),
                LocalDate.now().plusDays(5),
                request.itens(),
                999L
        );
        SyncPushRequest syncRequest = new SyncPushRequest(
                deviceId,
                List.of(new SyncPushRequest.MutacaoCliente(
                        "finance-sync-1",
                        "COMPRA",
                        purchaseId,
                        "ATUALIZAR_COMPRA",
                        canonicalVersion,
                        mapper.valueToTree(offlineUpdate),
                        LocalDateTime.now(),
                        "finance-sync-correlation"
                ))
        );
        SyncPushResponse synced = sync.push(syncRequest);
        SyncPushResponse replayedSync = sync.push(syncRequest);

        assertThat(synced.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(replayedSync.resultados().getFirst().status()).isEqualTo("APLICADA");
        assertThat(jdbc.queryForObject(
                "SELECT descricao FROM finance_compra WHERE id = ?",
                String.class,
                purchaseId
        )).isEqualTo("Brita graduada revisada offline");
        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM finance_compra WHERE id = ?",
                Long.class,
                purchaseId
        )).isEqualTo(rowVersionBeforeSync + 1);
    }

    @Test
    void betaCannotMutateSupplierSharedWithAnotherWorksite() {
        database = PdorMysqlTestDatabase.create("finance_supplier_scope");
        database.migrate();
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        String alfaId = collaborator(jdbc, "ALFA", "Gestora Global");
        String betaId = collaborator(jdbc, "BETA", "Gestora da Obra");
        String firstWorksiteId = worksite(jdbc);
        String secondWorksiteId = worksite(jdbc);
        authenticate(alfaId);

        CurrentUserService currentUser = new CurrentUserService(
                jdbc, new MockEnvironment(), false
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                org.mockito.Mockito.mock(ApplicationEventPublisher.class)
        );
        FinanceCatalogService catalog = new FinanceCatalogService(
                jdbc,
                currentUser,
                new FinanceOntologyProjector(memory)
        );
        FinanceDtos.SupplierRequest supplier = new FinanceDtos.SupplierRequest(
                null,
                "Fornecedor Compartilhado",
                null,
                "12.345.678/0001-95",
                "financeiro@fornecedor.example",
                null
        );
        transactions.execute(status -> catalog.saveSupplier(
                firstWorksiteId,
                supplier,
                FinanceAuditContext.online(alfaId, "supplier-first")
        ));
        transactions.execute(status -> catalog.saveSupplier(
                secondWorksiteId,
                supplier,
                FinanceAuditContext.online(alfaId, "supplier-second")
        ));

        authenticate(betaId);
        FinanceDtos.SupplierRequest unauthorizedUpdate =
                new FinanceDtos.SupplierRequest(
                        null,
                        "Nome alterado por escopo local",
                        null,
                        "12.345.678/0001-95",
                        "outro@fornecedor.example",
                        null
                );

        assertThatThrownBy(() -> transactions.execute(status ->
                catalog.saveSupplier(
                        firstWorksiteId,
                        unauthorizedUpdate,
                        FinanceAuditContext.online(betaId, "supplier-beta")
                )
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("compartilhado");
        assertThat(jdbc.queryForObject(
                "SELECT razao_social FROM finance_fornecedor WHERE cnpj_normalizado = ?",
                String.class,
                "12345678000195"
        )).isEqualTo("Fornecedor Compartilhado");
    }

    private String collaborator(JdbcTemplate jdbc, String role, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'teste', 'colaborador', ?, ?, ?, 1)
                """,
                id, id, name, role
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                id, "FIN-SERVICE-" + id, "Obra Financeira"
        );
        return id;
    }

    private void authenticate(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, userId);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }

    private void registerDevice(
            JdbcTemplate jdbc,
            String actorId,
            String deviceId
    ) {
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, usuario_id, ativo) VALUES (?, ?, 1)",
                deviceId,
                actorId
        );
        jdbc.update(
                "INSERT INTO sync_estado_dispositivo (dispositivo_id, ultimo_evento_recebido_seq, ultimo_evento_recebido_commit_seq) VALUES (?, 0, 0)",
                deviceId
        );
    }
}

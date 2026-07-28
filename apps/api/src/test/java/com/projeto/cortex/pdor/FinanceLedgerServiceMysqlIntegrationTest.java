package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos;
import com.projeto.cortex.financeiro.invoice.FinanceLedgerService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
class FinanceLedgerServiceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void validatesAllocationsSettlesPartiallyReplaysAndCancels() {
        database = PdorMysqlTestDatabase.create("fin_ledger_svc");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(database.dataSource())
        );
        String actorId = collaborator(jdbc);
        String obraId = worksite(jdbc);
        String supplierId = supplier(jdbc, actorId, obraId);
        String centerId = costCenter(jdbc, actorId, obraId);
        String categoryId = category(jdbc, actorId, obraId);
        String openStatusId = status(jdbc, actorId, obraId);
        authenticate(actorId);
        FinanceLedgerService service = service(jdbc);
        FinanceAuditContext audit = FinanceAuditContext.online(
                actorId, "ledger-integration"
        );
        String ledgerId = UUID.randomUUID().toString();
        FinanceInvoiceDtos.LedgerRequest request = ledgerRequest(
                ledgerId, "ledger-create-1", obraId, supplierId, centerId,
                categoryId, actorId, openStatusId,
                List.of(new FinanceInvoiceDtos.AllocationRequest(
                        null, centerId, categoryId,
                        new BigDecimal("940.0000"),
                        new BigDecimal("100.000000")
                ))
        );

        FinanceInvoiceDtos.LedgerResponse created = transactions.execute(
                status -> service.saveLedger(request, audit)
        );
        FinanceInvoiceDtos.LedgerResponse replay = transactions.execute(
                status -> service.saveLedger(request, audit)
        );
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(created.valorAberto()).isEqualByComparingTo("940.0000");
        assertThat(created.vencido()).isTrue();
        assertThat(service.listLedgers(new FinanceLedgerService.LedgerFilter(
                obraId, null, LocalDate.now(), "PAGAR", openStatusId,
                supplierId, actorId, centerId, categoryId, "Serviço", null,
                20, 0
        ))).extracting(FinanceInvoiceDtos.LedgerResponse::id)
                .containsExactly(ledgerId);

        FinanceInvoiceDtos.LedgerRequest invalidAllocation = ledgerRequest(
                UUID.randomUUID().toString(), "ledger-invalid-allocation",
                obraId, supplierId, centerId, categoryId, actorId,
                openStatusId,
                List.of(new FinanceInvoiceDtos.AllocationRequest(
                        null, centerId, categoryId,
                        new BigDecimal("900.0000"), null
                ))
        );
        assertThatThrownBy(() -> transactions.execute(status ->
                service.saveLedger(invalidAllocation, audit)
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("alocações");

        String settlementId = UUID.randomUUID().toString();
        FinanceInvoiceDtos.SettlementRequest settlement =
                new FinanceInvoiceDtos.SettlementRequest(
                        settlementId,
                        "settlement-create-1",
                        obraId,
                        "PAGAMENTO",
                        "EFETIVADA",
                        LocalDate.now(),
                        "BRL",
                        new BigDecimal("400.0000"),
                        "PIX",
                        "E2E-real-reference",
                        "Pagamento parcial",
                        List.of(new FinanceInvoiceDtos.SettlementAllocationRequest(
                                null, ledgerId, new BigDecimal("400.0000")
                        )),
                        null
                );
        FinanceInvoiceDtos.SettlementResponse settled = transactions.execute(
                status -> service.saveSettlement(settlement, audit)
        );
        FinanceInvoiceDtos.SettlementResponse settlementReplay =
                transactions.execute(status ->
                        service.saveSettlement(settlement, audit)
                );
        assertThat(settlementReplay.id()).isEqualTo(settled.id());
        assertThat(service.getLedger(ledgerId).valorLiquidado())
                .isEqualByComparingTo("400.0000");
        assertThat(service.getLedger(ledgerId).valorAberto())
                .isEqualByComparingTo("540.0000");

        transactions.executeWithoutResult(status -> service.cancelSettlement(
                settlementId,
                new FinanceInvoiceDtos.CancelSettlementRequest(
                        "settlement-cancel-1",
                        "Pagamento estornado pelo banco",
                        settled.versao()
                ),
                audit
        ));
        assertThat(service.getSettlement(settlementId).status())
                .isEqualTo("CANCELADA");
        assertThat(service.getLedger(ledgerId).valorLiquidado())
                .isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_liquidacao_historico WHERE liquidacao_id = ?",
                Integer.class,
                settlementId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_evento_operacional
                WHERE tipo_entidade = 'LIQUIDACAO' AND entidade_id = ?
                  AND usuario_id = ? AND correlacao_id = ?
                """,
                Integer.class,
                settlementId,
                actorId,
                "ledger-integration"
        )).isGreaterThan(0);
    }

    private FinanceInvoiceDtos.LedgerRequest ledgerRequest(
            String id,
            String mutation,
            String obraId,
            String supplierId,
            String centerId,
            String categoryId,
            String actorId,
            String statusId,
            List<FinanceInvoiceDtos.AllocationRequest> allocations
    ) {
        return new FinanceInvoiceDtos.LedgerRequest(
                id, mutation, obraId, null, supplierId, centerId, categoryId,
                actorId, statusId, "PAGAR", "MANUAL", "DOC-001",
                "Serviço executado", LocalDate.now().minusDays(10),
                LocalDate.now().minusDays(10), LocalDate.now().minusDays(1),
                "BRL", new BigDecimal("940.0000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("940.0000"), allocations, List.of(), null
        );
    }

    private FinanceLedgerService service(JdbcTemplate jdbc) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CurrentUserService currentUser = new CurrentUserService(
                jdbc, new MockEnvironment(), false
        );
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                org.mockito.Mockito.mock(ApplicationEventPublisher.class)
        );
        return new FinanceLedgerService(
                jdbc,
                currentUser,
                new FinanceOntologyProjector(memory),
                mapper,
                org.mockito.Mockito.mock(
                        com.projeto.cortex.obras.ObraOperabilityGuard.class
                )
        );
    }

    private String collaborator(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO colaborador (id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso, ativo) VALUES (?, 'teste', 'colaborador', ?, 'Gestora', 'ALFA', 1)",
                id, id
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra Financeira')",
                id, "LEDGER-" + id
        );
        return id;
    }

    private String supplier(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_fornecedor (id, razao_social, cnpj_normalizado, criado_por) VALUES (?, 'Fornecedor', ?, ?)",
                id, String.format("%014d", Math.abs(id.hashCode())), actor
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor_obra (id, fornecedor_id, obra_id, criado_por) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), id, obra, actor
        );
        return id;
    }

    private String costCenter(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_centro_custo (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Centro', ?)",
                id, obra, "CC-" + id, actor
        );
        return id;
    }

    private String category(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_categoria (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Categoria', ?)",
                id, obra, "CAT-" + id, actor
        );
        return id;
    }

    private String status(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_status_definicao (id, obra_id, agregado_tipo, codigo, nome, ordem, criado_por) VALUES (?, ?, 'LANCAMENTO', 'ABERTO', 'Aberto', 1, ?)",
                id, obra, actor
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
}

package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos;
import com.projeto.cortex.financeiro.invoice.FinanceReportService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinanceReportServiceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void totalsGroupingBudgetAndCsvUseTheSameRealScopedRows() {
        database = PdorMysqlTestDatabase.create("fin_report_svc");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String actorId = collaborator(jdbc);
        String worksiteId = worksite(jdbc);
        String supplierId = supplier(jdbc, actorId, worksiteId);
        String statusId = status(jdbc, actorId, worksiteId);
        String payableId = ledger(
                jdbc, actorId, worksiteId, supplierId, statusId,
                "PAGAR", "1000.0000", "400.0000",
                LocalDate.of(2026, 7, 10), "Serviço vencido"
        );
        ledger(
                jdbc, actorId, worksiteId, supplierId, statusId,
                "RECEBER", "500.0000", "100.0000",
                LocalDate.of(2026, 7, 20), "Recebimento futuro"
        );
        linkBudget(jdbc, actorId, worksiteId, payableId);
        FinanceReportService service = new FinanceReportService(jdbc);
        FinanceReportService.ReportFilter all = filter(
                worksiteId, null
        );

        FinanceInvoiceDtos.OverviewResponse overview = service.overview(all);
        assertThat(overview.previsto()).isEqualByComparingTo("1500.00");
        assertThat(overview.comprometido()).isEqualByComparingTo("1500.0000");
        assertThat(overview.liquidado()).isEqualByComparingTo("500.0000");
        assertThat(overview.aberto()).isEqualByComparingTo("1000.0000");
        assertThat(overview.vencido()).isEqualByComparingTo("600.0000");
        assertThat(overview.aPagar()).isEqualByComparingTo("600.0000");
        assertThat(overview.aReceber()).isEqualByComparingTo("400.0000");
        assertThat(overview.quantidadeLancamentos()).isEqualTo(2);
        assertThat(overview.quantidadeVencidos()).isEqualTo(1);
        assertThat(overview.possuiDados()).isTrue();
        assertThat(overview.possuiOrcamentoVinculado()).isTrue();

        FinanceReportService.ReportFilter payable = filter(
                worksiteId, "PAGAR"
        );
        List<FinanceInvoiceDtos.ReportRow> rows = service.report(
                "FORNECEDOR", payable
        );
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().grupoId()).isEqualTo(supplierId);
        assertThat(rows.getFirst().valorLiquido())
                .isEqualByComparingTo("1000.0000");
        assertThat(rows.getFirst().valorLiquidado())
                .isEqualByComparingTo("400.0000");
        assertThat(rows.getFirst().valorAberto())
                .isEqualByComparingTo("600.0000");

        String csv = new String(
                service.exportCsv("FORNECEDOR", payable),
                StandardCharsets.UTF_8
        );
        assertThat(csv).contains("1000.0000,400.0000,600.0000,1");
        assertThat(csv).doesNotContain("500.0000,100.0000,400.0000");
    }

    private FinanceReportService.ReportFilter filter(
            String worksiteId,
            String type
    ) {
        return new FinanceReportService.ReportFilter(
                worksiteId, "BRL", null, null, type, null, null, null,
                null, null, null, LocalDate.of(2026, 7, 14)
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
                id, "REPORT-" + id
        );
        return id;
    }

    private String supplier(
            JdbcTemplate jdbc,
            String actor,
            String worksite
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_fornecedor (id, razao_social, cnpj_normalizado, criado_por) VALUES (?, 'Fornecedor Real', ?, ?)",
                id, String.format("%014d", Math.abs(id.hashCode())), actor
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor_obra (id, fornecedor_id, obra_id, criado_por) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), id, worksite, actor
        );
        return id;
    }

    private String status(
            JdbcTemplate jdbc,
            String actor,
            String worksite
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_status_definicao (id, obra_id, agregado_tipo, codigo, nome, ordem, criado_por) VALUES (?, ?, 'LANCAMENTO', 'ABERTO', 'Aberto', 1, ?)",
                id, worksite, actor
        );
        return id;
    }

    private String ledger(
            JdbcTemplate jdbc,
            String actor,
            String worksite,
            String supplier,
            String status,
            String type,
            String net,
            String settled,
            LocalDate due,
            String description
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_lancamento (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    responsavel_id, status_id, tipo, origem, descricao,
                    data_competencia, vencimento_em, moeda, valor_original,
                    valor_liquido, valor_liquidado, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, 'BRL',
                          ?, ?, ?, ?)
                """,
                id, "report-" + id, worksite, supplier, actor, status, type,
                description, due, due, new BigDecimal(net),
                new BigDecimal(net), new BigDecimal(settled), actor
        );
        return id;
    }

    private void linkBudget(
            JdbcTemplate jdbc,
            String actor,
            String worksite,
            String ledger
    ) {
        String itemId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total
                ) VALUES (?, ?, 'CONTRATO-1', 'ITEM-1', 'Item real', 'UN',
                          1, 1500, 1500)
                """,
                itemId, worksite
        );
        jdbc.update(
                """
                INSERT INTO finance_lancamento_orcamento_vinculo (
                    id, lancamento_id, obra_id, item_contratual_id,
                    valor_vinculado, criado_por
                ) VALUES (?, ?, ?, ?, 1000, ?)
                """,
                UUID.randomUUID().toString(), ledger, worksite, itemId, actor
        );
    }
}

package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceInvoicesMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V32__finance_invoices_and_payments.sql"
    );

    @Test
    void definesInvoicesDocumentsLedgerAllocationsAndSettlements()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE finance_nota_fiscal")
                .contains("CREATE TABLE finance_nota_fiscal_compra")
                .contains("CREATE TABLE finance_nota_fiscal_documento")
                .contains("REFERENCES stored_object")
                .contains("CREATE TABLE finance_nota_fiscal_historico")
                .contains("CREATE TABLE finance_lancamento")
                .contains("CREATE TABLE finance_lancamento_alocacao")
                .contains("CREATE TABLE finance_lancamento_historico")
                .contains("CREATE TABLE finance_liquidacao")
                .contains("CREATE TABLE finance_liquidacao_lancamento")
                .contains("CREATE TABLE finance_liquidacao_historico")
                .contains("CREATE TABLE finance_lancamento_orcamento_vinculo");
    }

    @Test
    void keepsMoneyTraceabilityAndOcrStateProductionHonest()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("DECIMAL(19,4)")
                .contains("moeda CHAR(3)")
                .contains("client_mutation_id")
                .contains("correlacao_id")
                .contains("dispositivo_id")
                .contains("estado_anterior_json")
                .contains("estado_novo_json")
                .contains("ocr_status VARCHAR(30) NOT NULL DEFAULT 'NAO_CONFIGURADO'")
                .contains("CHECK (ocr_status IN (")
                .contains("arquivado_em")
                .contains("versao_linha")
                .doesNotContainIgnoringCase("INSERT INTO finance_")
                .doesNotContain("LONGBLOB")
                .doesNotContain("MEDIUMBLOB")
                .doesNotContain("conteudo_base64")
                .doesNotContain("resultado_ocr_json");
    }

    @Test
    void indexesRealInvoiceLedgerAndReportingFilters() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("uq_fin_nf_identidade_fornecedor")
                .contains("idx_fin_nf_obra_status_emissao")
                .contains("idx_fin_nf_fornecedor_numero")
                .contains("idx_fin_nf_responsavel")
                .contains("idx_fin_lancamento_obra_tipo_vencimento")
                .contains("idx_fin_lancamento_status_vencimento")
                .contains("idx_fin_lancamento_fornecedor")
                .contains("idx_fin_lancamento_responsavel")
                .contains("idx_fin_liquidacao_obra_data")
                .contains("UNIQUE (criado_por, client_mutation_id)");
    }
}

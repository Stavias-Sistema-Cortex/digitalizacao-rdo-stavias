package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceCoreMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V31__finance_core.sql"
    );

    @Test
    void definesNormalizedPurchaseWorkflowWithoutProductionSeedData()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE finance_fornecedor")
                .contains("CREATE TABLE finance_fornecedor_obra")
                .contains("fk_fin_compra_fornecedor_obra")
                .contains("CREATE TABLE finance_centro_custo")
                .contains("CREATE TABLE finance_categoria")
                .contains("CREATE TABLE finance_status_definicao")
                .contains("CREATE TABLE finance_status_transicao")
                .contains("CREATE TABLE finance_solicitacao_compra")
                .contains("CREATE TABLE finance_solicitacao_item")
                .contains("CREATE TABLE finance_compra")
                .contains("CREATE TABLE finance_compra_item")
                .contains("CREATE TABLE finance_status_historico")
                .doesNotContainIgnoringCase("INSERT INTO finance_")
                .doesNotContain("rdo_material")
                .doesNotContain("LONGBLOB")
                .doesNotContain("conteudo_base64");
    }

    @Test
    void persistsConfigurableApprovalRulesAndTraceableMutations()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE finance_regra_aprovacao")
                .contains("CREATE TABLE finance_regra_aprovacao_etapa")
                .contains("CREATE TABLE finance_aprovacao")
                .contains("CREATE TABLE finance_aprovacao_etapa")
                .contains("CREATE TABLE finance_aprovacao_decisao")
                .contains("valor_minimo DECIMAL(19,4)")
                .contains("valor_maximo DECIMAL(19,4)")
                .contains("vigente_de")
                .contains("vigente_ate")
                .contains("client_mutation_id")
                .contains("correlacao_id")
                .contains("dispositivo_id")
                .contains("estado_anterior_json")
                .contains("estado_novo_json")
                .contains("arquivado_em")
                .contains("versao_linha")
                .contains("DECIMAL(19,4)")
                .contains("moeda CHAR(3)");
    }

    @Test
    void indexesEveryRequiredPurchaseFilter() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("idx_fin_solicitacao_obra_status_periodo")
                .contains("idx_fin_solicitacao_responsavel")
                .contains("idx_fin_solicitacao_fornecedor")
                .contains("idx_fin_solicitacao_categoria")
                .contains("idx_fin_solicitacao_centro_custo")
                .contains("idx_fin_solicitacao_prioridade")
                .contains("idx_fin_compra_obra_status_periodo")
                .contains("UNIQUE (criado_por, client_mutation_id)");
    }
}

package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceControlUnitsMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V34__finance_control_units_allocations_and_assets.sql"
    );

    @Test
    void definesGeneralUnitsExactAllocationsAndIndividualAssets()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE finance_unidade_controle")
                .contains("'OBRA', 'ATIVO', 'ADMINISTRATIVO', 'CORPORATIVO'")
                .contains("INSERT INTO finance_unidade_controle")
                .contains("SELECT UUID(), 'OBRA'")
                .contains("CREATE TABLE finance_rateio")
                .contains("CREATE TABLE finance_rateio_item")
                .contains("CREATE TABLE finance_rateio_historico")
                .contains("CREATE TABLE finance_compra_item_ativo")
                .contains("CREATE TABLE finance_compra_item_ativo_historico")
                .contains("valor_alocado DECIMAL(19,4)")
                .contains("percentual DECIMAL(9,6)")
                .contains("client_mutation_id")
                .contains("estado_anterior_json")
                .contains("estado_novo_json")
                .contains("correlacao_id")
                .contains("dispositivo_id")
                .contains("natureza VARCHAR(20) NOT NULL DEFAULT 'CONSUMO'");
    }

    @Test
    void keepsCorporateScopeOutOfAutomaticDestinationsAndDoesNotFakeWorksites()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .doesNotContain("INSERT INTO obra")
                .doesNotContain("OBRA_CORPORATIVA")
                .contains("chk_fin_unidade_alvo")
                .contains("chk_fin_rateio_origem_unica")
                .contains("UNIQUE (compra_id)")
                .contains("UNIQUE (nota_fiscal_id)")
                .contains("UNIQUE (lancamento_id)")
                .contains("UNIQUE (rateio_id, ordem)")
                .contains("UNIQUE (compra_item_id, sequencia)")
                .contains("UNIQUE (ativo_id)")
                .contains("FOREIGN KEY (unidade_controle_id, ativo_id)");
    }

    @Test
    void marksLegacyWorksiteUnitsWithoutInventingAnActor() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("origem VARCHAR(30) NOT NULL")
                .contains("'MIGRACAO_OBRA', NULL, NULL")
                .doesNotContain("o.criado_por");
    }
}

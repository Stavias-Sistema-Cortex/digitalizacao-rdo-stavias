package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinancePurchasedAssetsMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V36__finance_purchased_asset_mutation_integrity.sql"
    );

    @Test
    void makesAssetConfirmationConcurrentAndAuditable() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("ALTER TABLE finance_compra_item_ativo_historico")
                .contains("client_mutation_id VARCHAR(120) NOT NULL")
                .contains("CREATE TABLE finance_compra_item_ativo_mutacao")
                .contains("PRIMARY KEY (ator_id, client_mutation_id)")
                .contains("compra_item_id CHAR(36) NOT NULL")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DELETE FROM");
    }
}

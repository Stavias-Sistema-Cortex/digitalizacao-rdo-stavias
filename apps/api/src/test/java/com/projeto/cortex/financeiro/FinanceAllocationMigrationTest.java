package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceAllocationMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V35__finance_allocation_mutation_integrity.sql"
    );

    @Test
    void makesEveryAllocationRevisionIdempotentPerActor() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("ALTER TABLE finance_rateio_historico")
                .contains("client_mutation_id VARCHAR(120) NOT NULL")
                .contains("UNIQUE (alterado_por, client_mutation_id)")
                .contains("CREATE TABLE finance_rateio_mutacao")
                .contains("PRIMARY KEY (ator_id, client_mutation_id)")
                .contains("origem_tipo VARCHAR(30) NOT NULL")
                .contains("rateio_id CHAR(36) NULL");
    }

    @Test
    void permitsMultipleCategorizedItemsForTheSameUnit() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("DROP INDEX uq_fin_rateio_item_unidade")
                .contains("CREATE INDEX idx_fin_rateio_item_rateio_unidade")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DELETE FROM");
    }
}

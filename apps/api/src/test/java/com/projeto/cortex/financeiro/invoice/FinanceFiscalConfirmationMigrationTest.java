package com.projeto.cortex.financeiro.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FinanceFiscalConfirmationMigrationTest {

    @Test
    void separatesUploadAndConfirmationMutationTrace() throws Exception {
        String sql;
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V38__finance_fiscal_document_confirmation_trace.sql"
        )) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("confirmacao_client_mutation_id VARCHAR(120)")
                .contains("confirmacao_dispositivo_id VARCHAR(120)")
                .contains("confirmacao_correlacao_id VARCHAR(120)")
                .contains("UNIQUE (confirmado_por, confirmacao_client_mutation_id)")
                .contains("legacy-documento-");
    }
}

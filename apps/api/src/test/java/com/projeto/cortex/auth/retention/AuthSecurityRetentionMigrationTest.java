package com.projeto.cortex.auth.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthSecurityRetentionMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V28__financial_permissions.sql"
    );

    @Test
    void keepsV28FinancialScopeAndAddsTheAuthRetentionIndexes()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE permissao_financeira_colaborador")
                .contains("FINANCEIRO_VISUALIZAR")
                .contains("FINANCEIRO_ADMINISTRAR")
                .contains("idx_auth_email_challenge_retention")
                .contains("auth_email_challenge (expira_em, id)")
                .contains("idx_auth_rate_limit_bucket_updated")
                .contains("auth_rate_limit_bucket (atualizado_em, bucket_key)")
                .doesNotContain("INSERT INTO permissao_financeira_colaborador")
                .doesNotContain("UPDATE permissao_financeira_colaborador");
    }
}

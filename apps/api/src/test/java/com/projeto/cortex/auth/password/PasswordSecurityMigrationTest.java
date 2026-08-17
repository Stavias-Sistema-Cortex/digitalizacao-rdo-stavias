package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PasswordSecurityMigrationTest {

    @Test
    void v87StoresOnlyArgonHashAndSingleUseCodeDigestWithAudit() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration-postgresql/V87__individual_password_authentication.sql"
        )) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE auth_password_credential")
                .contains("password_hash")
                .contains("CREATE TABLE auth_password_setup_challenge")
                .contains("codigo_digest")
                .contains("max_tentativas")
                .contains("CONSUMIDO")
                .contains("CREATE TABLE auth_password_audit")
                .doesNotContain("senha_texto")
                .doesNotContain("codigo_texto");
    }

    @Test
    void v88AddsMonotonicOfflineAuthorizationEpochAndRevocationTriggers()
            throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration-postgresql/"
                        + "V88__offline_password_vault_authorization_epoch.sql"
        )) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("ADD COLUMN auth_epoch bigint NOT NULL DEFAULT 1")
                .contains("CHECK (auth_epoch >= 1)")
                .contains("OLD.status = 'ATIVA'")
                .contains("NEW.status <> 'ATIVA'")
                .contains("OLD.ativo = TRUE")
                .contains("NEW.ativo = FALSE")
                .contains("NEW.deletado_em IS NOT NULL")
                .contains("SET auth_epoch = auth_epoch + 1");
    }
}

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
}

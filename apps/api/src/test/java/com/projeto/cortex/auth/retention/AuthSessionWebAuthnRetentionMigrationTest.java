package com.projeto.cortex.auth.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthSessionWebAuthnRetentionMigrationTest {

    @Test
    void addsOnlyTimestampLedRetentionIndexes() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V29__shared_storage_audit_and_auth_retention.sql"
        ));

        assertThat(sql)
                .contains("auth_session (expira_em, id)")
                .contains("auth_session (revogado_em, id)")
                .contains("auth_webauthn_challenge (expira_em, id)")
                .doesNotContain("DELETE FROM auth_session")
                .doesNotContain("DELETE FROM auth_webauthn_challenge");
    }
}

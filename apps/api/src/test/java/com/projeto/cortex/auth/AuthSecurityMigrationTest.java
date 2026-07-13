package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthSecurityMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V27__auth_security_and_pii_cleanup.sql"
    );

    @Test
    void createsSecurityTablesPreservesAlfaAndScrubsLegacyCpfEvidence() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("CREATE TABLE auth_identity");
        assertThat(sql).contains("CREATE TABLE auth_email_challenge");
        assertThat(sql).contains("CREATE TABLE auth_session");
        assertThat(sql).contains("CREATE TABLE auth_webauthn_credential");
        assertThat(sql).contains("WHERE papel_acesso IS NULL");
        assertThat(sql).doesNotContain("SET papel_acesso = 'ALFA'");
        assertThat(sql).contains("DELETE FROM cortex_evidencia_operacional");
        assertThat(sql).contains("JSON_REMOVE(snapshot_origem_json, '$.cpf_hash')");
        assertThat(sql).doesNotContain("DROP COLUMN cpf_hash");
    }
}

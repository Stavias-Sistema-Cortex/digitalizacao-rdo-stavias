package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SharedStorageSyncMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V29__shared_storage_audit_and_auth_retention.sql"
    );

    @Test
    void addsMetadataOnlyStorageScopedVisibilityAndExistingReceiptHardening()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE stored_object")
                .contains("sha256")
                .contains("storage_key")
                .contains("dedupe_key")
                .contains("CREATE TABLE cortex_evento_visibilidade")
                .contains("CONVERSATION_PARTICIPANT")
                .contains("FINANCIAL_CAPABILITY")
                .contains("ALTER TABLE sync_mutacao_cliente")
                .contains("proprietario_id")
                .contains("correlacao_id")
                .contains("erro_categoria")
                .contains("ALTER TABLE cortex_evento_operacional")
                .contains("estado_anterior_json")
                .contains("estado_novo_json")
                .contains("resultado")
                .doesNotContain("CREATE TABLE sync_mutation_receipt")
                .doesNotContain("ADD COLUMN resultado_json")
                .doesNotContain("LONGBLOB")
                .doesNotContain("MEDIUMBLOB")
                .doesNotContain("conteudo_base64");
    }

    @Test
    void keepsAuthRetentionIndexesInTheSameUndeployedV29() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("auth_session (expira_em, id)")
                .contains("auth_session (revogado_em, id)")
                .contains("auth_webauthn_challenge (expira_em, id)");
    }
}

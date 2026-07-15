package com.projeto.cortex.auth.roles;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminRoleMigrationTest {

    @Test
    void migrationAddsCapabilityImmutableHistoryAndIndexes() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V39__administrative_role_governance.sql"
        ));

        assertThat(sql)
                .contains("CREATE TABLE auth_capacidade_administrativa")
                .contains("ADMINISTRAR_PAPEIS")
                .contains("CREATE TABLE auth_papel_acesso_historico")
                .contains("papel_anterior")
                .contains("papel_novo")
                .contains("justificativa")
                .contains("idx_auth_papel_historico_alvo_data");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");
    }
}

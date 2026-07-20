package com.projeto.cortex.equipes;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EquipeMigrationTest {

    @Test
    void v40ShouldReconcileTemporalTeamsWithMessagingTeams() throws Exception {
        Path migration = Path.of(
                "src/main/resources/db/migration/V40__reconcile_temporal_equipes.sql"
        );
        assertThat(migration).exists();
        String sql = Files.readString(migration);

        assertThat(sql).contains("CREATE TABLE funcao_operacional");
        assertThat(sql).contains("ALTER TABLE equipe");
        assertThat(sql).contains("CREATE TABLE equipe_obra");
        assertThat(sql).contains("ALTER TABLE equipe_membro");

        assertThat(sql).contains("versao_linha BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("inicio_em DATETIME(6) NOT NULL");
        assertThat(sql).contains("fim_em DATETIME(6)");
        assertThat(sql).contains("CHECK (status IN ('ATIVO', 'REMOVIDO', 'ENCERRADO'))");
        assertThat(sql).contains("CHECK (status IN ('ATIVA', 'INATIVA', 'ARQUIVADA'))");

        assertThat(sql).contains("REFERENCES obra(id)");
        assertThat(sql).contains("REFERENCES funcao_operacional(id)");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");

        assertThat(sql).contains("obra_principal_id");
        assertThat(sql).contains("adicionado_por");
        assertThat(sql).doesNotContain("CREATE TRIGGER");
    }

}

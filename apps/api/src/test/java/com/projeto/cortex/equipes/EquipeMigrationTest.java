package com.projeto.cortex.equipes;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EquipeMigrationTest {

    @Test
    void v27ShouldCreateTemporalTeamsAndExtendSyncVocabulary() throws Exception {
        Path migration = Path.of(
                "src/main/resources/db/migration/V27__create_equipes_and_extend_sync.sql"
        );
        assertThat(migration).exists();
        String sql = Files.readString(migration);

        assertThat(sql).contains("CREATE TABLE funcao_operacional");
        assertThat(sql).contains("CREATE TABLE equipe");
        assertThat(sql).contains("CREATE TABLE equipe_obra");
        assertThat(sql).contains("CREATE TABLE equipe_membro");

        assertThat(sql).contains("versao_linha BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("inicio_em DATETIME(6) NOT NULL");
        assertThat(sql).contains("fim_em DATETIME(6)");
        assertThat(sql).contains("CHECK (status IN ('ATIVO', 'ENCERRADO'))");
        assertThat(sql).contains("CHECK (status IN ('ATIVA', 'INATIVA', 'ARQUIVADA'))");

        assertThat(sql).contains("REFERENCES obra(id)");
        assertThat(sql).contains("REFERENCES colaborador(id)");
        assertThat(sql).contains("REFERENCES funcao_operacional(id)");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");

        assertThat(sql).contains("DROP CHECK chk_sync_mutacao_operacao");
        assertThat(sql).contains("'CRIAR_EQUIPE'");
        assertThat(sql).contains("'ATUALIZAR_EQUIPE'");
        assertThat(sql).contains("'ARQUIVAR_EQUIPE'");
        assertThat(sql).contains("'ADICIONAR_MEMBRO_EQUIPE'");
        assertThat(sql).contains("'ATUALIZAR_MEMBRO_EQUIPE'");
        assertThat(sql).contains("'ENCERRAR_MEMBRO_EQUIPE'");
    }
}

package com.projeto.cortex.pdoc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdocMigrationTest {

    @Test
    void v16ShouldDeclareStableMysqlSnapshotSchema() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V16__create_pdoc_snapshot.sql"
        ));

        assertThat(sql).contains("CREATE TABLE pdoc_snapshot");
        assertThat(sql).contains("id CHAR(36) NOT NULL");
        assertThat(sql).contains("obra_id CHAR(36) NOT NULL");
        assertThat(sql).contains("evento_origem_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        assertThat(sql).contains("chave_idempotencia CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");

        assertThat(sql).contains("inputs_json JSON NOT NULL");
        assertThat(sql).contains("origem_inputs_json JSON NOT NULL");
        assertThat(sql).contains("warnings_json JSON NOT NULL");
        assertThat(sql).contains("drivers_json JSON NOT NULL");

        assertThat(sql).contains("p50_custo DECIMAL(18,2)");
        assertThat(sql).contains("eac_ponderado DECIMAL(18,2)");
        assertThat(sql).contains("cpi DECIMAL(12,6)");
        assertThat(sql).contains("prob_exceder_5_pct DECIMAL(9,6)");
        assertThat(sql).contains("confianca DECIMAL(9,6)");

        assertThat(sql).contains("UNIQUE (chave_idempotencia)");
        assertThat(sql).contains("REFERENCES obra(id)");
        assertThat(sql).contains("REFERENCES cortex_evento_operacional(id)");
        assertThat(sql).contains("ON DELETE RESTRICT");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");

        assertThat(sql).contains("CHECK (status_execucao IN ('SUCCESS', 'INSUFFICIENT_DATA', 'FAILED'))");
        assertThat(sql).contains("CHECK (tipo_disparo IN ('MANUAL', 'EVENT', 'SCHEDULED', 'API'))");
        assertThat(sql).contains("ON pdoc_snapshot (obra_id, executado_em, criado_em, id)");
        assertThat(sql).contains("ENGINE = InnoDB");
        assertThat(sql).contains("DEFAULT CHARACTER SET = utf8mb4");
    }
}

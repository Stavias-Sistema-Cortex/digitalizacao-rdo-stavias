package com.projeto.cortex.pdor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdorMigrationTest {

    @Test
    void v25ShouldDeclareStableMysqlSnapshotSchema() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V25__create_pdor_snapshot.sql"
        ));

        assertThat(sql).contains("CREATE TABLE pdor_snapshot");
        assertThat(sql).contains("id CHAR(36) NOT NULL");
        assertThat(sql).contains("obra_id CHAR(36) NOT NULL");
        assertThat(sql).contains("evento_origem_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        assertThat(sql).contains("chave_idempotencia CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");

        assertThat(sql).contains("inputs_json JSON NOT NULL");
        assertThat(sql).contains("origem_inputs_json JSON NOT NULL");
        assertThat(sql).contains("warnings_json JSON NOT NULL");
        assertThat(sql).contains("drivers_json JSON NOT NULL");

        assertThat(sql).contains("p50_receita DECIMAL(18,2)");
        assertThat(sql).contains("rac_ponderado DECIMAL(18,2)");
        assertThat(sql).contains("rci DECIMAL(12,6)");
        assertThat(sql).contains("prob_abaixo_95_pct DECIMAL(9,6)");
        assertThat(sql).contains("confianca DECIMAL(9,6)");

        assertThat(sql).contains("UNIQUE (chave_idempotencia)");
        assertThat(sql).contains("REFERENCES obra(id)");
        assertThat(sql).contains("REFERENCES cortex_evento_operacional(id)");
        assertThat(sql).contains("ON DELETE RESTRICT");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");

        assertThat(sql).contains("CHECK (status_execucao IN ('SUCCESS', 'INSUFFICIENT_DATA', 'FAILED'))");
        assertThat(sql).contains("CHECK (tipo_disparo IN ('MANUAL', 'EVENT', 'SCHEDULED', 'API'))");
        assertThat(sql).contains("ON pdor_snapshot (obra_id, executado_em, criado_em, id)");
        assertThat(sql).contains("ENGINE = InnoDB");
        assertThat(sql).contains("DEFAULT CHARACTER SET = utf8mb4");
    }

    @Test
    void v30ShouldAddReproducibleExplainabilityAndInitiatorMetadata() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V30__strengthen_pdor_explainability.sql"
        ));

        assertThat(sql).contains("versao_dados CHAR(64)");
        assertThat(sql).contains("escopo_analise_json JSON");
        assertThat(sql).contains("janela_temporal_json JSON");
        assertThat(sql).contains("features_utilizadas_json JSON");
        assertThat(sql).contains("dados_ausentes_json JSON");
        assertThat(sql).contains("limitacoes_json JSON");
        assertThat(sql).contains("alertas_json JSON");
        assertThat(sql).contains("recomendacoes_json JSON");
        assertThat(sql).contains("comparacao_anterior_json JSON");
        assertThat(sql).contains("evidencias_json JSON");
        assertThat(sql).contains("iniciado_por VARCHAR(120)");
        assertThat(sql).contains("tipo_iniciador VARCHAR(20)");
        assertThat(sql).contains("USER", "PROCESS", "UNKNOWN");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");
    }
}

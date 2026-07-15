package com.projeto.cortex.obras.mapa;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ObraGeometriaMigrationTest {

    @Test
    void v29CreatesTemporalGeospatialFeaturesWithoutCascadeDeletion() throws Exception {
        Path migration = Path.of(
                "src/main/resources/db/migration/V29__create_obra_geometria.sql"
        );
        assertThat(migration).exists();
        String sql = Files.readString(migration);

        assertThat(sql).contains("CREATE TABLE obra_geometria");
        assertThat(sql).contains("geometria_json JSON NOT NULL");
        assertThat(sql).contains("propriedades_json JSON NOT NULL");
        assertThat(sql).contains("versao_linha BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("valido_desde DATETIME(6) NOT NULL");
        assertThat(sql).contains("valido_ate DATETIME(6)");
        assertThat(sql).contains("REFERENCES obra(id)");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");
        assertThat(sql).contains("LOCALIZACAO_OBRA");
        assertThat(sql).contains("PERIMETRO_OBRA");
        assertThat(sql).contains("PROGRAMACAO");
    }
}

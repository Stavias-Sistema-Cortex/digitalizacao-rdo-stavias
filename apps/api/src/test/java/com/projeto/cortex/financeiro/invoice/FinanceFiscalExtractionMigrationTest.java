package com.projeto.cortex.financeiro.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceFiscalExtractionMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V37__finance_fiscal_document_extraction.sql"
    );

    @Test
    void persistsImmutableJobsCandidatesHashesAndRealActors() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE finance_documento_extracao_job")
                .contains("CREATE TABLE finance_documento_extracao_candidato")
                .contains("client_mutation_id VARCHAR(120) NOT NULL")
                .contains("sha256_cliente CHAR(64)")
                .contains("sha256_servidor CHAR(64)")
                .contains("COLLATE ascii_bin NOT NULL")
                .contains("resultado_json JSON")
                .contains("avisos_json JSON")
                .contains("confianca DECIMAL(7,6) NOT NULL")
                .contains("localizacao VARCHAR(500)")
                .contains("criado_por CHAR(36) NOT NULL")
                .contains("dispositivo_id VARCHAR(120)")
                .contains("correlacao_id VARCHAR(120)")
                .contains("UNIQUE (criado_por, client_mutation_id)")
                .contains("FOREIGN KEY (stored_object_id, obra_id)")
                .contains("REFERENCES stored_object(id, obra_id)");
    }

    @Test
    void extendsInvoiceLinksWithoutLosingLegacyAuthorship() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("ALTER TABLE finance_nota_fiscal_documento")
                .contains("ADD COLUMN enviado_por CHAR(36)")
                .contains("ADD COLUMN enviado_em DATETIME(6)")
                .contains("ADD COLUMN confirmado_por CHAR(36)")
                .contains("ADD COLUMN confirmado_em DATETIME(6)")
                .contains("ADD COLUMN extracao_job_id CHAR(36)")
                .contains("ADD COLUMN inspecao_status VARCHAR(30)")
                .contains("SET d.enviado_por = d.criado_por")
                .contains("confirmado_por = d.criado_por")
                .contains("sha256_servidor = o.sha256")
                .contains("MODIFY enviado_por CHAR(36) NOT NULL")
                .contains("MODIFY confirmado_por CHAR(36) NOT NULL")
                .contains("MODIFY sha256_servidor CHAR(64)")
                .contains("COLLATE ascii_bin NOT NULL")
                .doesNotContain("DROP COLUMN criado_por");
    }

    @Test
    void constrainsExtractionStateAndNeverClaimsFiscalAuthorization() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("'PENDENTE', 'PROCESSANDO', 'EXTRAIDO'")
                .contains("'REVISAO_NECESSARIA', 'FALHA'")
                .contains("'NAO_VERIFICADA'")
                .contains("CHECK (confianca >= 0 AND confianca <= 1)")
                .contains("CHECK (sha256_servidor REGEXP '^[0-9a-f]{64}$')")
                .doesNotContain("AUTORIZADA_SEFAZ")
                .doesNotContain("AUTORIZACAO_CONFIRMADA");
    }
}

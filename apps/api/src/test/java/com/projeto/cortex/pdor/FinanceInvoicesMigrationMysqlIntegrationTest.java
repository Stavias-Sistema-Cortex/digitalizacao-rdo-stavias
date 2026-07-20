package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinanceInvoicesMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void migratesV1ThroughV32AndPersistsScopedInvoicePaymentGraph() {
        database = PdorMysqlTestDatabase.create("finance_v32");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        String actorId = collaborator(jdbc, "Gestora Financeira");
        String obraId = worksite(jdbc, "A");
        String supplierId = supplier(jdbc, actorId, obraId);
        String centerId = costCenter(jdbc, actorId, obraId);
        String categoryId = category(jdbc, actorId, obraId);
        String invoiceStatusId = status(
                jdbc, actorId, obraId, "NOTA_FISCAL", "RECEBIDA"
        );
        String ledgerStatusId = status(
                jdbc, actorId, obraId, "LANCAMENTO", "ABERTO"
        );
        String invoiceId = UUID.randomUUID().toString();
        String ledgerId = UUID.randomUUID().toString();
        String settlementId = UUID.randomUUID().toString();

        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    centro_custo_id, categoria_id, responsavel_id,
                    status_id, numero, serie, tipo_documento, data_emissao,
                    vencimento_em, moeda, valor_bruto, desconto, acrescimo,
                    retencoes, valor_liquido, criado_por
                ) VALUES (?, 'nf-client-1', ?, ?, ?, ?, ?, ?, '12345', '1',
                          'NFE', ?, ?, 'BRL', 1000.0000, 50.0000,
                          10.0000, 20.0000, 940.0000, ?)
                """,
                invoiceId,
                obraId,
                supplierId,
                centerId,
                categoryId,
                actorId,
                invoiceStatusId,
                LocalDate.now(),
                LocalDate.now().plusDays(10),
                actorId
        );

        String storedObjectId = storedObject(jdbc, actorId, obraId);
        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal_documento (
                    id, nota_fiscal_id, obra_id, stored_object_id,
                    tipo_documento, principal, criado_por
                ) VALUES (?, ?, ?, ?, 'DANFE', TRUE, ?)
                """,
                UUID.randomUUID().toString(),
                invoiceId,
                obraId,
                storedObjectId,
                actorId
        );

        jdbc.update(
                """
                INSERT INTO finance_lancamento (
                    id, client_mutation_id, obra_id, nota_fiscal_id,
                    fornecedor_id, centro_custo_id, categoria_id,
                    responsavel_id, status_id, tipo, origem, descricao,
                    data_competencia, vencimento_em, moeda, valor_original,
                    desconto, juros, multa, valor_liquido, criado_por
                ) VALUES (?, 'ledger-client-1', ?, ?, ?, ?, ?, ?, ?,
                          'PAGAR', 'NOTA_FISCAL', 'Pagamento da NF 12345',
                          ?, ?, 'BRL', 940.0000, 0.0000, 0.0000, 0.0000,
                          940.0000, ?)
                """,
                ledgerId,
                obraId,
                invoiceId,
                supplierId,
                centerId,
                categoryId,
                actorId,
                ledgerStatusId,
                LocalDate.now(),
                LocalDate.now().plusDays(10),
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_lancamento_alocacao (
                    id, lancamento_id, obra_id, centro_custo_id,
                    categoria_id, valor_alocado, criado_por
                ) VALUES (?, ?, ?, ?, ?, 940.0000, ?)
                """,
                UUID.randomUUID().toString(),
                ledgerId,
                obraId,
                centerId,
                categoryId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_liquidacao (
                    id, client_mutation_id, obra_id, tipo, data_liquidacao,
                    moeda, valor_total, meio, referencia_bancaria,
                    criado_por
                ) VALUES (?, 'settlement-client-1', ?, 'PAGAMENTO', ?,
                          'BRL', 400.0000, 'PIX', 'E2E-real-reference', ?)
                """,
                settlementId,
                obraId,
                LocalDate.now(),
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_liquidacao_lancamento (
                    id, liquidacao_id, lancamento_id, obra_id,
                    valor_aplicado, criado_por
                ) VALUES (?, ?, ?, ?, 400.0000, ?)
                """,
                UUID.randomUUID().toString(),
                settlementId,
                ledgerId,
                obraId,
                actorId
        );

        assertThat(jdbc.queryForObject(
                "SELECT valor_liquido FROM finance_nota_fiscal WHERE id = ?",
                BigDecimal.class,
                invoiceId
        )).isEqualByComparingTo("940.0000");
        assertThat(jdbc.queryForObject(
                "SELECT SUM(valor_aplicado) FROM finance_liquidacao_lancamento WHERE lancamento_id = ?",
                BigDecimal.class,
                ledgerId
        )).isEqualByComparingTo("400.0000");
        assertThat(jdbc.queryForObject(
                "SELECT ocr_status FROM finance_nota_fiscal WHERE id = ?",
                String.class,
                invoiceId
        )).isEqualTo("NAO_CONFIGURADO");

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_nota_fiscal (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    centro_custo_id, categoria_id, responsavel_id,
                    status_id, numero, serie, tipo_documento, data_emissao,
                    moeda, valor_bruto, desconto, acrescimo, retencoes,
                    valor_liquido, criado_por
                ) VALUES (?, 'nf-client-duplicate', ?, ?, ?, ?, ?, ?,
                          '12345', '1', 'NFE', ?, 'BRL', 1.0000, 0.0000,
                          0.0000, 0.0000, 1.0000, ?)
                """,
                UUID.randomUUID().toString(),
                obraId,
                supplierId,
                centerId,
                categoryId,
                actorId,
                invoiceStatusId,
                LocalDate.now(),
                actorId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsCrossWorksiteDocumentAndInvalidInvoiceTotals() {
        database = PdorMysqlTestDatabase.create("finance_scope_v32");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String actorId = collaborator(jdbc, "Gestor");
        String obraA = worksite(jdbc, "A");
        String obraB = worksite(jdbc, "B");
        String supplierId = supplier(jdbc, actorId, obraA);
        String centerId = costCenter(jdbc, actorId, obraA);
        String categoryId = category(jdbc, actorId, obraA);
        String statusId = status(
                jdbc, actorId, obraA, "NOTA_FISCAL", "RECEBIDA"
        );
        String invoiceId = UUID.randomUUID().toString();
        insertMinimalInvoice(
                jdbc, invoiceId, actorId, obraA, supplierId,
                centerId, categoryId, statusId
        );
        String objectFromOtherWorksite = storedObject(jdbc, actorId, obraB);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_nota_fiscal_documento (
                    id, nota_fiscal_id, obra_id, stored_object_id,
                    tipo_documento, principal, criado_por
                ) VALUES (?, ?, ?, ?, 'DANFE', TRUE, ?)
                """,
                UUID.randomUUID().toString(),
                invoiceId,
                obraA,
                objectFromOtherWorksite,
                actorId
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_nota_fiscal (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    centro_custo_id, categoria_id, responsavel_id,
                    status_id, numero, serie, tipo_documento, data_emissao,
                    moeda, valor_bruto, desconto, acrescimo, retencoes,
                    valor_liquido, criado_por
                ) VALUES (?, 'nf-invalid-total', ?, ?, ?, ?, ?, ?, '999',
                          '1', 'NFE', ?, 'BRL', 100.0000, 0.0000, 0.0000,
                          0.0000, 99.0000, ?)
                """,
                UUID.randomUUID().toString(),
                obraA,
                supplierId,
                centerId,
                categoryId,
                actorId,
                statusId,
                LocalDate.now(),
                actorId
        )).isInstanceOf(DataAccessException.class);
    }

    private void insertMinimalInvoice(
            JdbcTemplate jdbc,
            String invoiceId,
            String actorId,
            String obraId,
            String supplierId,
            String centerId,
            String categoryId,
            String statusId
    ) {
        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    centro_custo_id, categoria_id, responsavel_id,
                    status_id, numero, serie, tipo_documento, data_emissao,
                    moeda, valor_bruto, desconto, acrescimo, retencoes,
                    valor_liquido, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '100', '1', 'NFE', ?,
                          'BRL', 100.0000, 0.0000, 0.0000, 0.0000,
                          100.0000, ?)
                """,
                invoiceId,
                "nf-" + invoiceId,
                obraId,
                supplierId,
                centerId,
                categoryId,
                actorId,
                statusId,
                LocalDate.now(),
                actorId
        );
    }

    private String collaborator(JdbcTemplate jdbc, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, ativo
                ) VALUES (?, 'teste', 'colaborador', ?, ?, 1)
                """,
                id,
                id,
                name
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc, String suffix) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                id,
                "FIN-V32-" + suffix + "-" + id,
                "Obra Financeira " + suffix
        );
        return id;
    }

    private String supplier(
            JdbcTemplate jdbc,
            String actorId,
            String obraId
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_fornecedor (
                    id, razao_social, cnpj_normalizado, criado_por
                ) VALUES (?, ?, ?, ?)
                """,
                id,
                "Fornecedor " + id,
                cnpjFor(id),
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_fornecedor_obra (
                    id, fornecedor_id, obra_id, criado_por
                ) VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                id,
                obraId,
                actorId
        );
        return id;
    }

    private String cnpjFor(String id) {
        long value = Math.abs(id.hashCode()) % 10_000_000_000_000L;
        return String.format("%014d", value);
    }

    private String costCenter(
            JdbcTemplate jdbc,
            String actorId,
            String obraId
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_centro_custo (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Centro', ?)",
                id,
                obraId,
                "CC-" + id,
                actorId
        );
        return id;
    }

    private String category(
            JdbcTemplate jdbc,
            String actorId,
            String obraId
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_categoria (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Categoria', ?)",
                id,
                obraId,
                "CAT-" + id,
                actorId
        );
        return id;
    }

    private String status(
            JdbcTemplate jdbc,
            String actorId,
            String obraId,
            String aggregate,
            String code
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome, ordem,
                    criado_por
                ) VALUES (?, ?, ?, ?, ?, 1, ?)
                """,
                id,
                obraId,
                aggregate,
                code,
                code,
                actorId
        );
        return id;
    }

    private String storedObject(
            JdbcTemplate jdbc,
            String actorId,
            String obraId
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, proprietario_id, obra_id, dedupe_key, sha256,
                    storage_backend, storage_key, nome_original,
                    media_type_detectado, tamanho_bytes, criado_por,
                    disponivel_em
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL', ?, 'nota.pdf',
                          'application/pdf', 128, ?, CURRENT_TIMESTAMP(6))
                """,
                id,
                actorId,
                obraId,
                hex64(id + "dedupe"),
                hex64(id + "sha"),
                "finance/" + obraId + "/" + id,
                actorId
        );
        return id;
    }

    private String hex64(String value) {
        return String.format("%064x", Math.abs(value.hashCode()));
    }
}

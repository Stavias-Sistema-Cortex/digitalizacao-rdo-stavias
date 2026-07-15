package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos;
import com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionRepository;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinanceFiscalExtractionMigrationMysqlIntegrationTest {

    private static final String SHA = "a".repeat(64);

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void backfillsLegacyLinkAndEnforcesJobCandidateIntegrity() {
        database = PdorMysqlTestDatabase.create("finance_extract_v37");
        migrateToV36();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        String actorId = collaborator(jdbc);
        String worksiteId = worksite(jdbc, "Obra fiscal");
        String invoiceId = invoice(jdbc, actorId, worksiteId);
        String objectId = storedObject(jdbc, actorId, worksiteId, SHA);
        String documentId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal_documento (
                    id, nota_fiscal_id, obra_id, stored_object_id,
                    tipo_documento, principal, criado_por
                ) VALUES (?, ?, ?, ?, 'XML', TRUE, ?)
                """,
                documentId,
                invoiceId,
                worksiteId,
                objectId,
                actorId
        );

        database.migrate();

        assertThat(jdbc.queryForMap(
                """
                SELECT enviado_por, confirmado_por, sha256_servidor,
                       inspecao_status
                FROM finance_nota_fiscal_documento
                WHERE id = ?
                """,
                documentId
        )).containsEntry("enviado_por", actorId)
                .containsEntry("confirmado_por", actorId)
                .containsEntry("sha256_servidor", SHA)
                .containsEntry("inspecao_status", "APROVADO");

        String jobId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_documento_extracao_job (
                    id, stored_object_id, obra_id, client_mutation_id,
                    formato, status, extrator, extrator_versao,
                    sha256_cliente, sha256_servidor,
                    autorizacao_fiscal_status, criado_por,
                    dispositivo_id, correlacao_id, iniciado_em, concluido_em
                ) VALUES (?, ?, ?, 'extract-mutation-1', 'XML', 'EXTRAIDO',
                          'XML_FISCAL', '1', ?, ?, 'NAO_VERIFICADA', ?,
                          'device-1', 'correlation-1',
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
                jobId,
                objectId,
                worksiteId,
                SHA,
                SHA,
                actorId
        );
        assertThat(jdbc.update(
                """
                INSERT INTO finance_documento_extracao_candidato (
                    id, job_id, campo, valor_normalizado_json,
                    texto_original, extrator, extrator_versao,
                    localizacao, confianca, validacao_status
                ) VALUES (?, ?, 'valorLiquido', JSON_OBJECT('valor', 125.50),
                          '125.50', 'XML_FISCAL', '1',
                          '/NFe/infNFe/total/ICMSTot/vNF', 1.000000, 'VALIDO')
                """,
                UUID.randomUUID().toString(),
                jobId
        )).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_documento_extracao_candidato (
                    id, job_id, campo, valor_normalizado_json,
                    extrator, extrator_versao, confianca, validacao_status
                ) VALUES (?, ?, 'numero', JSON_OBJECT('valor', '1'),
                          'XML_FISCAL', '1', 1.100000, 'VALIDO')
                """,
                UUID.randomUUID().toString(),
                jobId
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_documento_extracao_job (
                    id, stored_object_id, obra_id, client_mutation_id,
                    formato, status, sha256_servidor,
                    autorizacao_fiscal_status, criado_por
                ) VALUES (?, ?, ?, 'extract-mutation-2', 'XML', 'PENDENTE',
                          'hash-invalido', 'NAO_VERIFICADA', ?)
                """,
                UUID.randomUUID().toString(),
                objectId,
                worksiteId,
                actorId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void repositoryPersistsAndRehydratesCanonicalCandidates() {
        database = PdorMysqlTestDatabase.create("finance_extract_repo");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String actorId = collaborator(jdbc);
        String worksiteId = worksite(jdbc, "Obra de extração");
        String objectId = storedObject(jdbc, actorId, worksiteId, SHA);
        FiscalExtractionRepository repository = new FiscalExtractionRepository(
                jdbc,
                new ObjectMapper().findAndRegisterModules()
        );
        String jobId = UUID.randomUUID().toString();
        FiscalExtractionDtos.FiscalCandidate candidate =
                new FiscalExtractionDtos.FiscalCandidate(
                        "numero", TextNode.valueOf("125"), "125",
                        "NFE_XML", "1", "/NFe/infNFe/ide/nNF",
                        new BigDecimal("1.000000"),
                        FiscalExtractionDtos.CandidateValidation.VALIDO,
                        null
                );
        FiscalExtractionDtos.FiscalExtractionResult result =
                new FiscalExtractionDtos.FiscalExtractionResult(
                        "XML",
                        FiscalExtractionDtos.ExtractionStatus.EXTRAIDO,
                        "NFE_XML",
                        "1",
                        List.of(candidate),
                        List.of(),
                        "NAO_VERIFICADA"
                );

        repository.insert(
                new FiscalExtractionRepository.JobWrite(
                        jobId, objectId, worksiteId, "repo-mutation-1",
                        "XML", SHA, SHA, actorId, "device-1",
                        "correlation-1"
                ),
                result
        );

        var response = repository.response(jobId, false);
        assertThat(response.status())
                .isEqualTo(FiscalExtractionDtos.ExtractionStatus.EXTRAIDO);
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().getFirst().normalizedValue().asText())
                .isEqualTo("125");
        assertThat(repository.findByMutation(actorId, "repo-mutation-1"))
                .get().extracting(FiscalExtractionRepository.JobRecord::id)
                .isEqualTo(jobId);
    }

    private void migrateToV36() {
        Flyway.configure()
                .dataSource(
                        database.jdbcUrl(),
                        "root",
                        PdorMysqlTestDatabase.rootPassword()
                )
                .locations("filesystem:src/main/resources/db/migration")
                .target(MigrationVersion.fromVersion("36"))
                .load()
                .migrate();
    }

    private String collaborator(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    ativo, papel_acesso
                ) VALUES (?, 'teste', 'colaborador', ?,
                          'Gestor fiscal', 1, 'ALFA')
                """,
                id,
                id
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, nome, status)
                VALUES (?, ?, ?, 'ATIVA')
                """,
                id,
                "NF-" + id,
                name
        );
        return id;
    }

    private String invoice(
            JdbcTemplate jdbc,
            String actorId,
            String worksiteId
    ) {
        String supplierId = UUID.randomUUID().toString();
        String statusId = UUID.randomUUID().toString();
        String invoiceId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_fornecedor (
                    id, razao_social, cnpj_normalizado, criado_por
                ) VALUES (?, 'Fornecedor fiscal', '12345678000195', ?)
                """,
                supplierId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_fornecedor_obra (
                    id, fornecedor_id, obra_id, criado_por
                ) VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                supplierId,
                worksiteId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome, ordem,
                    terminal, criado_por
                ) VALUES (?, ?, 'NOTA_FISCAL', 'RECEBIDA', 'Recebida',
                          1, 0, ?)
                """,
                statusId,
                worksiteId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    responsavel_id, status_id, numero, serie,
                    tipo_documento, data_emissao, moeda,
                    valor_bruto, valor_liquido, criado_por
                ) VALUES (?, 'invoice-mutation-v37', ?, ?, ?, ?, '100', '1',
                          'NFE', CURRENT_DATE, 'BRL', 125.5000, 125.5000, ?)
                """,
                invoiceId,
                worksiteId,
                supplierId,
                actorId,
                statusId,
                actorId
        );
        return invoiceId;
    }

    private String storedObject(
            JdbcTemplate jdbc,
            String actorId,
            String worksiteId,
            String sha
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, proprietario_id, obra_id, dedupe_key, sha256,
                    storage_backend, storage_key, nome_original,
                    media_type_declarado, media_type_detectado,
                    tamanho_bytes, status, criado_por, disponivel_em
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL', ?, 'nota.xml',
                          'application/xml', 'application/xml', 100,
                          'DISPONIVEL', ?, CURRENT_TIMESTAMP(6))
                """,
                id,
                actorId,
                worksiteId,
                "b".repeat(64),
                sha,
                "objects/test/" + id,
                actorId
        );
        return id;
    }
}

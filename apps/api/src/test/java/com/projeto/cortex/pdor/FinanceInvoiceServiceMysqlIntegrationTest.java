package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinanceInvoiceServiceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void createsReplaysFiltersUpdatesDocumentsAndArchivesInvoice() {
        database = PdorMysqlTestDatabase.create("fin_nf_svc");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(database.dataSource())
        );
        String actorId = collaborator(jdbc);
        String obraId = worksite(jdbc);
        String supplierId = supplier(jdbc, actorId, obraId);
        String centerId = costCenter(jdbc, actorId, obraId);
        String categoryId = category(jdbc, actorId, obraId);
        String receivedStatusId = status(
                jdbc, actorId, obraId, "RECEBIDA", 1
        );
        String approvedStatusId = status(
                jdbc, actorId, obraId, "APROVADA", 2
        );
        jdbc.update(
                """
                INSERT INTO finance_status_transicao (
                    id, obra_id, agregado_tipo, status_origem_id,
                    status_destino_id, criado_por
                ) VALUES (?, ?, 'NOTA_FISCAL', ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                obraId,
                receivedStatusId,
                approvedStatusId,
                actorId
        );
        authenticate(actorId);
        FinanceInvoiceService service = service(jdbc);
        FinanceAuditContext audit = FinanceAuditContext.online(
                actorId, "invoice-integration"
        );
        String invoiceId = UUID.randomUUID().toString();
        FinanceInvoiceDtos.InvoiceRequest create = request(
                invoiceId,
                "invoice-create-1",
                obraId,
                supplierId,
                centerId,
                categoryId,
                actorId,
                receivedStatusId,
                "12345",
                null
        );

        FinanceInvoiceDtos.InvoiceResponse created = transactions.execute(
                status -> service.save(create, audit)
        );
        FinanceInvoiceDtos.InvoiceResponse replay = transactions.execute(
                status -> service.save(create, audit)
        );
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(service.list(new FinanceInvoiceService.InvoiceFilter(
                obraId,
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1),
                actorId,
                supplierId,
                receivedStatusId,
                centerId,
                categoryId,
                "12345",
                20,
                0
        ))).extracting(FinanceInvoiceDtos.InvoiceResponse::id)
                .containsExactly(invoiceId);

        String objectId = storedObject(jdbc, actorId, obraId);
        FinanceInvoiceDtos.InvoiceDocumentResponse document =
                transactions.execute(status -> service.linkDocument(
                        invoiceId,
                        new FinanceInvoiceDtos.DocumentLinkRequest(
                                null, objectId, "DANFE", true
                        ),
                        audit
                ));
        assertThat(service.documents(invoiceId, obraId))
                .extracting(FinanceInvoiceDtos.InvoiceDocumentResponse::id)
                .containsExactly(document.id());
        assertThat(service.documentObjectId(invoiceId, document.id(), obraId))
                .isEqualTo(objectId);

        long versionBeforeUpdate = service.get(invoiceId).versao();
        FinanceInvoiceDtos.InvoiceRequest update = request(
                invoiceId,
                "invoice-update-1",
                obraId,
                supplierId,
                centerId,
                categoryId,
                actorId,
                approvedStatusId,
                "12345",
                versionBeforeUpdate
        );
        FinanceInvoiceDtos.InvoiceResponse updated = transactions.execute(
                status -> service.save(update, audit)
        );
        assertThat(updated.statusCodigo()).isEqualTo("APROVADA");
        assertThat(updated.versao()).isEqualTo(versionBeforeUpdate + 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_nota_fiscal_historico WHERE nota_fiscal_id = ?",
                Integer.class,
                invoiceId
        )).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_evento_operacional
                WHERE tipo_entidade = 'NOTA_FISCAL' AND entidade_id = ?
                  AND usuario_id = ? AND correlacao_id = ?
                """,
                Integer.class,
                invoiceId,
                actorId,
                "invoice-integration"
        )).isGreaterThan(0);

        transactions.executeWithoutResult(status -> service.archive(
                invoiceId,
                updated.versao(),
                "invoice-archive-1",
                audit
        ));
        assertThat(service.list(new FinanceInvoiceService.InvoiceFilter(
                obraId, null, null, null, null, null, null, null,
                null, 20, 0
        ))).isEmpty();
    }

    @Test
    void rejectsInvalidStatusTransitionAndCrossWorksiteObject() {
        database = PdorMysqlTestDatabase.create("fin_nf_scope");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(database.dataSource())
        );
        String actorId = collaborator(jdbc);
        String obraA = worksite(jdbc);
        String obraB = worksite(jdbc);
        String supplierId = supplier(jdbc, actorId, obraA);
        String centerId = costCenter(jdbc, actorId, obraA);
        String categoryId = category(jdbc, actorId, obraA);
        String receivedStatusId = status(jdbc, actorId, obraA, "RECEBIDA", 1);
        String blockedStatusId = status(jdbc, actorId, obraA, "BLOQUEADA", 2);
        authenticate(actorId);
        FinanceInvoiceService service = service(jdbc);
        FinanceAuditContext audit = FinanceAuditContext.online(actorId, null);
        String invoiceId = UUID.randomUUID().toString();
        FinanceInvoiceDtos.InvoiceResponse created = transactions.execute(
                status -> service.save(request(
                        invoiceId, "invoice-scope-create", obraA, supplierId,
                        centerId, categoryId, actorId, receivedStatusId,
                        "200", null
                ), audit)
        );

        assertThatThrownBy(() -> transactions.execute(status -> service.save(
                request(
                        invoiceId, "invoice-invalid-transition", obraA,
                        supplierId, centerId, categoryId, actorId,
                        blockedStatusId, "200", created.versao()
                ),
                audit
        ))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("transição");

        String objectFromB = storedObject(jdbc, actorId, obraB);
        assertThatThrownBy(() -> transactions.execute(status ->
                service.linkDocument(
                        invoiceId,
                        new FinanceInvoiceDtos.DocumentLinkRequest(
                                null, objectFromB, "DANFE", true
                        ),
                        audit
                )
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("objeto");
    }

    private FinanceInvoiceService service(JdbcTemplate jdbc) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CurrentUserService currentUser = new CurrentUserService(
                jdbc, new MockEnvironment(), false
        );
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                org.mockito.Mockito.mock(ApplicationEventPublisher.class)
        );
        return new FinanceInvoiceService(
                jdbc,
                currentUser,
                new FinanceOntologyProjector(memory),
                mapper
        );
    }

    private FinanceInvoiceDtos.InvoiceRequest request(
            String id,
            String mutation,
            String obraId,
            String supplierId,
            String centerId,
            String categoryId,
            String actorId,
            String statusId,
            String number,
            Long baseVersion
    ) {
        return new FinanceInvoiceDtos.InvoiceRequest(
                id,
                mutation,
                obraId,
                supplierId,
                centerId,
                categoryId,
                actorId,
                statusId,
                number,
                "1",
                null,
                "NFE",
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now().plusDays(10),
                "BRL",
                new BigDecimal("1000.0000"),
                new BigDecimal("50.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("940.0000"),
                "Documento fiscal real",
                List.of(),
                baseVersion
        );
    }

    private String collaborator(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'teste', 'colaborador', ?, 'Gestora', 'ALFA', 1)
                """,
                id,
                id
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra Financeira')",
                id,
                "NF-" + id
        );
        return id;
    }

    private String supplier(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_fornecedor (id, razao_social, cnpj_normalizado, criado_por) VALUES (?, 'Fornecedor', ?, ?)",
                id,
                String.format("%014d", Math.abs(id.hashCode())),
                actor
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor_obra (id, fornecedor_id, obra_id, criado_por) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                id,
                obra,
                actor
        );
        return id;
    }

    private String costCenter(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_centro_custo (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Centro', ?)",
                id, obra, "CC-" + id, actor
        );
        return id;
    }

    private String category(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_categoria (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, ?, 'Categoria', ?)",
                id, obra, "CAT-" + id, actor
        );
        return id;
    }

    private String status(
            JdbcTemplate jdbc,
            String actor,
            String obra,
            String code,
            int order
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_status_definicao (id, obra_id, agregado_tipo, codigo, nome, ordem, criado_por) VALUES (?, ?, 'NOTA_FISCAL', ?, ?, ?, ?)",
                id, obra, code, code, order, actor
        );
        return id;
    }

    private String storedObject(JdbcTemplate jdbc, String actor, String obra) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, proprietario_id, obra_id, dedupe_key, sha256,
                    storage_backend, storage_key, nome_original,
                    media_type_detectado, tamanho_bytes, status, criado_por,
                    disponivel_em
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL', ?, 'nota.pdf',
                          'application/pdf', 128, 'DISPONIVEL', ?,
                          CURRENT_TIMESTAMP(6))
                """,
                id,
                actor,
                obra,
                hex(id + "dedupe"),
                hex(id + "sha"),
                "objects/finance/" + id,
                actor
        );
        return id;
    }

    private String hex(String value) {
        return String.format("%064x", Math.abs(value.hashCode()));
    }

    private void authenticate(String actorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                actorId
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }
}

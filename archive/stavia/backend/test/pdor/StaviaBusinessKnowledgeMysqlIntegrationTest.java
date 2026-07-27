package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.intelligence.stavia.knowledge.finance.JdbcFinanceOperationalKnowledgeReader;
import com.projeto.cortex.intelligence.stavia.knowledge.message.JdbcMessageSyncKnowledgeReader;
import com.projeto.cortex.mensagens.domain.MessageWorksiteAccessService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class StaviaBusinessKnowledgeMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void readsTypedFinanceAndScopedMessageEvidenceFromV33Schema() {
        database = PdorMysqlTestDatabase.create("stavia_biz");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String alfa = collaborator(jdbc, "ALFA", "Gestora Alfa");
        String beta = collaborator(jdbc, "BETA", "Operadora Beta");
        String obraA = worksite(jdbc, "A");
        String obraB = worksite(jdbc, "B");
        worksiteLink(jdbc, beta, obraA);

        FinanceSeed finance = seedFinance(jdbc, alfa, obraA);
        seedMessaging(jdbc, alfa, beta, obraA, "A");
        seedMessaging(jdbc, alfa, alfa, obraB, "B");

        JdbcFinanceOperationalKnowledgeReader financeReader =
                new JdbcFinanceOperationalKnowledgeReader(jdbc);
        assertThat(financeReader.overdueInvoices(
                obraA,
                LocalDate.of(2026, 7, 14)
        )).singleElement().satisfies(invoice -> {
            assertThat(invoice.invoiceId()).isEqualTo(finance.invoiceId());
            assertThat(invoice.openAmount()).isEqualByComparingTo("750.00");
        });
        assertThat(financeReader.purchaseHistory(obraA, "PED-42"))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.purchaseId()).isEqualTo(finance.purchaseId());
                    assertThat(history.createdById()).isEqualTo(alfa);
                    assertThat(history.newStatus()).isEqualTo("EMITIDA");
                });
        assertThat(financeReader.purchasedTotal(
                obraA,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1)
        )).singleElement().satisfies(total -> {
            assertThat(total.currency()).isEqualTo("BRL");
            assertThat(total.total()).isEqualByComparingTo("9800.00");
            assertThat(total.purchaseCount()).isEqualTo(1);
        });
        assertThat(financeReader.suppliersWithPendingCharges(obraA))
                .singleElement()
                .satisfies(supplier -> {
                    assertThat(supplier.supplierId())
                            .isEqualTo(finance.supplierId());
                    assertThat(supplier.pendingCount()).isEqualTo(1);
                });
        assertThat(financeReader.overdueInvoices(
                obraB,
                LocalDate.of(2026, 7, 14)
        )).isEmpty();

        CurrentUserService currentUsers = new CurrentUserService(
                jdbc,
                new MockEnvironment(),
                false
        );
        MessageWorksiteAccessService messageAccess =
                new MessageWorksiteAccessService(jdbc, currentUsers);
        JdbcMessageSyncKnowledgeReader messageReader =
                new JdbcMessageSyncKnowledgeReader(jdbc, messageAccess);

        assertThat(messageAccess.canRead(beta, obraA)).isTrue();
        assertThat(messageAccess.canRead(beta, obraB)).isFalse();
        assertThat(messageReader.pendingDocuments(obraA, beta))
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.storageStatus()).isEqualTo("TEMPORARIO");
                    assertThat(document.syncStatus()).isEqualTo("PENDENTE");
                    assertThat(document.syncErrorCategory()).isNull();
                });
        assertThat(messageReader.pendingDocuments(obraB, beta)).isEmpty();
        assertThat(messageReader.pendingDocuments(obraB, alfa))
                .singleElement();
    }

    private FinanceSeed seedFinance(
            JdbcTemplate jdbc,
            String actor,
            String worksite
    ) {
        String supplier = id();
        String center = id();
        String category = id();
        String purchaseStatus = status(
                jdbc, actor, worksite, "COMPRA", "EMITIDA"
        );
        String invoiceStatus = status(
                jdbc, actor, worksite, "NOTA_FISCAL", "RECEBIDA"
        );
        String ledgerStatus = status(
                jdbc, actor, worksite, "LANCAMENTO", "ABERTO"
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor (id, razao_social, cnpj_normalizado, email_financeiro, criado_por) VALUES (?, 'Fornecedor Real', ?, 'financeiro@example.test', ?)",
                supplier,
                digits14(supplier),
                actor
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor_obra (id, fornecedor_id, obra_id, criado_por) VALUES (?, ?, ?, ?)",
                id(), supplier, worksite, actor
        );
        jdbc.update(
                "INSERT INTO finance_centro_custo (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, 'CC-1', 'Operação', ?)",
                center, worksite, actor
        );
        jdbc.update(
                "INSERT INTO finance_categoria (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, 'MAT', 'Materiais', ?)",
                category, worksite, actor
        );

        String purchase = id();
        jdbc.update(
                """
                INSERT INTO finance_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, fornecedor_id, responsavel_compra_id,
                    status_id, numero_pedido, descricao, prioridade, moeda,
                    valor_contratado, pedido_emitido_em, criado_por, criado_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PED-42', 'Compra rastreável',
                          'MEDIA', 'BRL', 9800.00, '2026-07-10', ?,
                          '2026-07-10 09:00:00')
                """,
                purchase, "purchase-" + purchase, worksite, center, category,
                supplier, actor, purchaseStatus, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_status_historico (
                    id, entidade_tipo, entidade_id, obra_id, status_novo_id,
                    ator_id, origem, estado_novo_json, resultado, ocorrido_em
                ) VALUES (?, 'COMPRA', ?, ?, ?, ?, 'ONLINE', JSON_OBJECT(),
                          'SUCESSO', '2026-07-10 09:00:00')
                """,
                id(), purchase, worksite, purchaseStatus, actor
        );

        String invoice = id();
        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    responsavel_id, status_id, numero, serie, tipo_documento,
                    data_emissao, vencimento_em, moeda, valor_bruto,
                    valor_liquido, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, 'NF-88', 'A', 'NFE',
                          '2026-06-01', '2026-07-01', 'BRL', 1000.00,
                          1000.00, ?)
                """,
                invoice, "invoice-" + invoice, worksite, supplier, actor,
                invoiceStatus, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_lancamento (
                    id, client_mutation_id, obra_id, nota_fiscal_id,
                    fornecedor_id, responsavel_id, status_id, tipo, origem,
                    descricao, data_competencia, vencimento_em, moeda,
                    valor_original, valor_liquido, valor_liquidado, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PAGAR', 'NOTA_FISCAL',
                          'Parcela da nota', '2026-06-01', '2026-07-01', 'BRL',
                          1000.00, 1000.00, 250.00, ?)
                """,
                id(), "ledger-" + invoice, worksite, invoice, supplier, actor,
                ledgerStatus, actor
        );

        String sender = id();
        String template = id();
        jdbc.update(
                "INSERT INTO finance_email_perfil_remetente (id, codigo, nome, configuracao_remetente_chave, criado_por) VALUES (?, ?, 'Financeiro', ?, ?)",
                sender, "FIN-" + sender, "EMAIL-" + sender, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_modelo_email (
                    id, obra_id, codigo, nome, versao, assunto_template,
                    corpo_texto_template, variaveis_permitidas_json, criado_por
                ) VALUES (?, ?, 'COBRANCA', 'Cobrança', 1, 'Cobrança',
                          'Mensagem', JSON_ARRAY(), ?)
                """,
                template, worksite, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_cobranca_email (
                    id, client_mutation_id, idempotency_key, obra_id,
                    modelo_email_id, perfil_remetente_id, fornecedor_id,
                    nota_fiscal_id, responsavel_id, modo, status,
                    destinatario, ocorrencia_prevista_em,
                    dados_renderizacao_json, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', 'RASCUNHO',
                          'financeiro@example.test', '2026-07-14 08:00:00',
                          JSON_OBJECT(), ?)
                """,
                id(), "charge-" + invoice, "charge-key-" + invoice,
                worksite, template, sender, supplier, invoice, actor, actor
        );
        return new FinanceSeed(supplier, purchase, invoice);
    }

    private void seedMessaging(
            JdbcTemplate jdbc,
            String creator,
            String participant,
            String worksite,
            String suffix
    ) {
        String conversation = id();
        String message = id();
        String storedObject = id();
        String attachment = id();
        String device = id();
        jdbc.update(
                "INSERT INTO conversa (id, tipo, obra_id, titulo, criado_por) VALUES (?, 'OBRA', ?, ?, ?)",
                conversation, worksite, "Conversa " + suffix, creator
        );
        jdbc.update(
                "INSERT INTO conversa_participante (id, conversa_id, colaborador_id, adicionado_por) VALUES (?, ?, ?, ?)",
                id(), conversation, participant, creator
        );
        jdbc.update(
                """
                INSERT INTO mensagem (
                    id, conversa_id, autor_id, corpo, client_mutation_id,
                    criado_cliente_em
                ) VALUES (?, ?, ?, 'Documento em processamento', ?,
                          CURRENT_TIMESTAMP(6))
                """,
                message, conversation, creator, "message-" + message
        );
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, proprietario_id, obra_id, dedupe_key, sha256,
                    storage_backend, storage_key, media_type_detectado,
                    tamanho_bytes, status, criado_por
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL', ?, 'application/pdf',
                          42, 'TEMPORARIO', ?)
                """,
                storedObject, creator, worksite, hex64(storedObject + "d"),
                hex64(storedObject + "s"), "messages/" + storedObject, creator
        );
        jdbc.update(
                "INSERT INTO mensagem_anexo (id, mensagem_id, stored_object_id, criado_por) VALUES (?, ?, ?, ?)",
                attachment, message, storedObject, creator
        );
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, nome, usuario_id) VALUES (?, 'Teste', ?)",
                device, creator
        );
        jdbc.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id, dispositivo_id, proprietario_id, client_mutation_id,
                    entidade_tipo, entidade_id, operacao, payload_json,
                    payload_hash, status
                ) VALUES (?, ?, ?, ?, 'MENSAGEM_ANEXO', ?,
                          'ADICIONAR_MENSAGEM_ANEXO', JSON_OBJECT(), ?,
                          'PENDENTE')
                """,
                id(), device, creator, "attachment-" + attachment,
                attachment, hex64(attachment)
        );
    }

    private String collaborator(
            JdbcTemplate jdbc,
            String role,
            String name
    ) {
        String value = id();
        jdbc.update(
                "INSERT INTO colaborador (id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso, ativo) VALUES (?, 'test', 'colaborador', ?, ?, ?, 1)",
                value, value, name, role
        );
        return value;
    }

    private String worksite(JdbcTemplate jdbc, String suffix) {
        String value = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                value, "STAVIA-" + value, "Obra " + suffix
        );
        return value;
    }

    private void worksiteLink(
            JdbcTemplate jdbc,
            String collaborator,
            String worksite
    ) {
        jdbc.update(
                "INSERT INTO vinculo_colaborador_obra (id, obra_id, colaborador_id, atribuido_por) VALUES (?, ?, ?, 'integration-test')",
                id(), worksite, collaborator
        );
    }

    private String status(
            JdbcTemplate jdbc,
            String actor,
            String worksite,
            String aggregate,
            String code
    ) {
        String value = id();
        jdbc.update(
                "INSERT INTO finance_status_definicao (id, obra_id, agregado_tipo, codigo, nome, ordem, criado_por) VALUES (?, ?, ?, ?, ?, 1, ?)",
                value, worksite, aggregate, code, code, actor
        );
        return value;
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private String digits14(String seed) {
        return String.format("%014d", Integer.toUnsignedLong(seed.hashCode()));
    }

    private String hex64(String seed) {
        return java.util.HexFormat.of().formatHex(
                MessageDigestHolder.sha256(seed)
        );
    }

    private record FinanceSeed(
            String supplierId,
            String purchaseId,
            String invoiceId
    ) {
    }

    private static final class MessageDigestHolder {
        private MessageDigestHolder() {
        }

        static byte[] sha256(String value) {
            try {
                return java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}

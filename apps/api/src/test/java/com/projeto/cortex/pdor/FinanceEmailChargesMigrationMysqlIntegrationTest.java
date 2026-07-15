package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
class FinanceEmailChargesMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void migratesV1ThroughV33AndEnforcesPreviewAndSendIdempotency() {
        database = PdorMysqlTestDatabase.create("finance_email_v33");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String actor = collaborator(jdbc);
        String worksite = worksite(jdbc);
        String supplier = supplier(jdbc, actor, worksite);
        String ledger = ledger(jdbc, actor, worksite, supplier);
        String sender = UUID.randomUUID().toString();
        String reply = UUID.randomUUID().toString();
        String template = UUID.randomUUID().toString();
        String rule = UUID.randomUUID().toString();
        String charge = UUID.randomUUID().toString();
        LocalDateTime occurrence = LocalDateTime.of(2026, 7, 20, 9, 0);
        LocalDateTime deliveryTime = LocalDateTime.now().minusSeconds(1);

        jdbc.update(
                """
                INSERT INTO finance_email_perfil_remetente (
                    id, codigo, nome, configuracao_remetente_chave, criado_por
                ) VALUES (?, 'FINANCEIRO', 'Financeiro Stavias',
                          'EMAIL_FINANCE_AUTHENTICATED_SENDER', ?)
                """,
                sender, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_email_reply_to_permitido (
                    id, obra_id, colaborador_id, nome, email, criado_por
                ) VALUES (?, ?, ?, 'Responsável da obra',
                          'resposta@example.com', ?)
                """,
                reply, worksite, actor, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_modelo_email (
                    id, obra_id, codigo, nome, versao, status,
                    assunto_template, corpo_texto_template,
                    variaveis_permitidas_json, previsualizacao_hash,
                    previsualizado_por, previsualizado_em, criado_por
                ) VALUES (?, ?, 'COBRANCA_VENCIMENTO', 'Cobrança', 1,
                          'ATIVO', 'Pagamento {{numeroDocumento}}',
                          'Olá {{fornecedorNome}}',
                          JSON_ARRAY('numeroDocumento', 'fornecedorNome'),
                          ?, ?, CURRENT_TIMESTAMP(6), ?)
                """,
                template, worksite, hash('a'), actor, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_regra_cobranca (
                    id, client_mutation_id, obra_id, modelo_email_id,
                    perfil_remetente_id, reply_to_permitido_id, nome, modo,
                    fuso_horario, status, previsualizacao_hash,
                    previsualizacao_confirmada_por,
                    previsualizacao_confirmada_em, criado_por
                ) VALUES (?, 'rule-create-1', ?, ?, ?, ?, 'Antes do vencimento',
                          'AUTOMATICA', 'America/Sao_Paulo', 'ATIVA', ?, ?,
                          CURRENT_TIMESTAMP(6), ?)
                """,
                rule, worksite, template, sender, reply, hash('b'), actor, actor
        );
        insertCharge(
                jdbc, charge, "charge-create-1", "charge-key-1", worksite,
                rule, template, sender, reply, supplier, ledger, actor,
                occurrence
        );
        jdbc.update(
                """
                INSERT INTO finance_cobranca_tentativa (
                    id, cobranca_id, obra_id, numero_tentativa,
                    idempotency_key, resultado, provider,
                    provider_message_id, iniciada_em, concluida_em, criado_por
                ) VALUES (?, ?, ?, 1, 'charge-key-1:attempt:1', 'ENVIADA',
                          'fake', 'fake-message-1', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), charge, worksite,
                occurrence, occurrence.plusSeconds(1), actor
        );
        jdbc.update(
                """
                INSERT INTO finance_cobranca_evento_entrega (
                    id, cobranca_id, obra_id, provider, provider_event_id,
                    provider_message_id, tipo_evento, payload_hash,
                    ocorrido_em, autenticado_em
                ) VALUES (?, ?, ?, 'fake', 'fake-event-1', 'fake-message-1',
                          'ENTREGUE', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), charge, worksite, hash('c'),
                deliveryTime, deliveryTime
        );

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_cobranca_tentativa WHERE cobranca_id = ?",
                Integer.class,
                charge
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT tipo_evento FROM finance_cobranca_evento_entrega WHERE cobranca_id = ?",
                String.class,
                charge
        )).isEqualTo("ENTREGUE");

        assertThatThrownBy(() -> insertCharge(
                jdbc, UUID.randomUUID().toString(), "charge-create-2",
                "charge-key-1", worksite, rule, template, sender, reply,
                supplier, ledger, actor, occurrence.plusDays(1)
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertCharge(
                jdbc, UUID.randomUUID().toString(), "charge-create-3",
                "charge-key-3", worksite, rule, template, sender, reply,
                supplier, ledger, actor, occurrence
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_regra_cobranca (
                    id, client_mutation_id, obra_id, modelo_email_id,
                    perfil_remetente_id, nome, modo, fuso_horario,
                    status, criado_por
                ) VALUES (?, 'rule-invalid', ?, ?, ?, 'Regra sem preview',
                          'AUTOMATICA', 'America/Sao_Paulo', 'ATIVA', ?)
                """,
                UUID.randomUUID().toString(), worksite, template, sender, actor
        )).isInstanceOf(DataAccessException.class);
    }

    private void insertCharge(
            JdbcTemplate jdbc,
            String id,
            String mutation,
            String idempotency,
            String worksite,
            String rule,
            String template,
            String sender,
            String reply,
            String supplier,
            String ledger,
            String actor,
            LocalDateTime occurrence
    ) {
        jdbc.update(
                """
                INSERT INTO finance_cobranca_email (
                    id, client_mutation_id, idempotency_key, obra_id,
                    regra_id, modelo_email_id, perfil_remetente_id,
                    reply_to_permitido_id, fornecedor_id, lancamento_id,
                    responsavel_id, modo, status, destinatario,
                    vencimento_em, ocorrencia_prevista_em,
                    dados_renderizacao_json, previsualizacao_hash,
                    previsualizacao_confirmada_por,
                    previsualizacao_confirmada_em, proxima_tentativa_em,
                    criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTOMATICA',
                          'NA_FILA', 'financeiro@example.com', ?, ?,
                          JSON_OBJECT('lancamentoId', ?), ?, ?,
                          CURRENT_TIMESTAMP(6), ?, ?)
                """,
                id, mutation, idempotency, worksite, rule, template, sender,
                reply, supplier, ledger, actor, LocalDate.of(2026, 7, 20),
                occurrence, ledger, hash('d'), actor, occurrence, actor
        );
    }

    private String collaborator(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO colaborador (id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso, ativo) VALUES (?, 'teste', 'colaborador', ?, 'Gestora', 'ALFA', 1)",
                id, id
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra de cobranças')",
                id, "EMAIL-" + id
        );
        return id;
    }

    private String supplier(
            JdbcTemplate jdbc,
            String actor,
            String worksite
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_fornecedor (id, razao_social, cnpj_normalizado, email_financeiro, criado_por) VALUES (?, 'Fornecedor', ?, 'financeiro@example.com', ?)",
                id, String.format("%014d", Math.abs(id.hashCode())), actor
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor_obra (id, fornecedor_id, obra_id, criado_por) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), id, worksite, actor
        );
        return id;
    }

    private String ledger(
            JdbcTemplate jdbc,
            String actor,
            String worksite,
            String supplier
    ) {
        String status = UUID.randomUUID().toString();
        String ledger = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_status_definicao (id, obra_id, agregado_tipo, codigo, nome, ordem, criado_por) VALUES (?, ?, 'LANCAMENTO', 'ABERTO', 'Aberto', 1, ?)",
                status, worksite, actor
        );
        jdbc.update(
                """
                INSERT INTO finance_lancamento (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    responsavel_id, status_id, tipo, origem, descricao,
                    data_competencia, vencimento_em, moeda, valor_original,
                    valor_liquido, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, 'RECEBER', 'MANUAL', 'Cobrança',
                          ?, ?, 'BRL', 1000, 1000, ?)
                """,
                ledger, "ledger-" + ledger, worksite, supplier, actor, status,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 20), actor
        );
        return ledger;
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}

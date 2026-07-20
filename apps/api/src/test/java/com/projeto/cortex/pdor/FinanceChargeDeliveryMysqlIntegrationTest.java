package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.email.FakeEmailGateway;
import com.projeto.cortex.email.EmailGateway;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeDeliveryService;
import com.projeto.cortex.financeiro.cobranca.FinanceAutomaticChargeService;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeCatalogService;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeOccurrenceCalculator;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.ChargeRequest;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.RuleRequest;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.TargetReference;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeRetryPolicy;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeService;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeTargetReader;
import com.projeto.cortex.financeiro.cobranca.FinanceEmailSenderConfiguration;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinanceChargeDeliveryMysqlIntegrationTest {

    private static final String SENDER_KEY =
            "EMAIL_FINANCE_AUTHENTICATED_SENDER";

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void previewsQueuesAndDeliversOneProviderMessageAcrossReplay() {
        database = PdorMysqlTestDatabase.create("charge_delivery");
        database.migrate();
        javax.sql.DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String actor = collaborator(jdbc);
        String worksite = worksite(jdbc);
        String supplier = supplier(jdbc, actor, worksite);
        String ledger = ledger(jdbc, actor, worksite, supplier);
        String sender = sender(jdbc, actor);
        String template = template(jdbc, actor, worksite);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FinanceChargeTargetReader targetReader =
                new FinanceChargeTargetReader(jdbc);
        FinanceEmailSenderConfiguration senderConfiguration =
                new FinanceEmailSenderConfiguration(SENDER_KEY);
        FinanceOntologyProjector ontology =
                mock(FinanceOntologyProjector.class);
        FinanceChargeService service = new FinanceChargeService(
                jdbc,
                objectMapper,
                targetReader,
                senderConfiguration,
                ontology
        );
        FakeEmailGateway gateway = new FakeEmailGateway();
        FinanceChargeDeliveryService delivery =
                new FinanceChargeDeliveryService(
                        jdbc,
                        objectMapper,
                        gateway,
                        targetReader,
                        senderConfiguration,
                        new FinanceChargeRetryPolicy(4, 120, 3600),
                        ontology,
                        new DataSourceTransactionManager(dataSource),
                        120
                );

        String chargeId = service.create(
                new ChargeRequest(
                        null,
                        "charge-create-1",
                        worksite,
                        template,
                        sender,
                        null,
                        "LANCAMENTO",
                        ledger,
                        "MANUAL",
                        null
                ),
                actor,
                "integration-charge-1"
        ).id();
        service.preview(
                chargeId, worksite, actor, "integration-charge-preview-1"
        );
        service.queueNow(
                chargeId, worksite, actor, "integration-charge-queue-1"
        );

        delivery.dispatchOne(chargeId);
        delivery.dispatchOne(chargeId);

        assertThat(gateway.capturedMessages()).hasSize(1);
        assertThat(gateway.capturedMessages().getFirst().recipient())
                .isEqualTo("financeiro@example.com");
        assertThat(gateway.capturedMessages().getFirst().subject())
                .isEqualTo("Cobrança DOC-42 — Obra de cobranças");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM finance_cobranca_email WHERE id = ?",
                String.class,
                chargeId
        )).isEqualTo("ENVIADA");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_cobranca_tentativa WHERE cobranca_id = ?",
                Integer.class,
                chargeId
        )).isEqualTo(1);

        String failedChargeId = service.create(
                new ChargeRequest(
                        null,
                        "charge-create-safe-failure",
                        worksite,
                        template,
                        sender,
                        null,
                        "LANCAMENTO",
                        ledger,
                        "MANUAL",
                        null
                ),
                actor,
                "integration-charge-failure"
        ).id();
        service.preview(
                failedChargeId, worksite, actor,
                "integration-charge-failure-preview"
        );
        service.queueNow(
                failedChargeId, worksite, actor,
                "integration-charge-failure-queue"
        );
        EmailGateway safeFailure = message -> {
            throw new EmailGateway.DeliveryException(
                    "PROVIDER_TEMPORARIO",
                    "Falha temporária sanitizada.",
                    true
            );
        };
        FinanceChargeDeliveryService failingDelivery =
                new FinanceChargeDeliveryService(
                        jdbc,
                        objectMapper,
                        safeFailure,
                        targetReader,
                        senderConfiguration,
                        new FinanceChargeRetryPolicy(4, 120, 3600),
                        ontology,
                        new DataSourceTransactionManager(dataSource),
                        120
                );
        failingDelivery.dispatchOne(failedChargeId);

        assertThat(jdbc.queryForMap(
                "SELECT status, erro_categoria, erro_sanitizado, proxima_tentativa_em FROM finance_cobranca_email WHERE id = ?",
                failedChargeId
        )).containsEntry("status", "FALHOU")
                .containsEntry("erro_categoria", "PROVIDER_TEMPORARIO")
                .containsEntry(
                        "erro_sanitizado", "Falha temporária sanitizada."
                ).containsKey("proxima_tentativa_em");

        FinanceChargeCatalogService catalog =
                new FinanceChargeCatalogService(
                        jdbc,
                        objectMapper,
                        targetReader,
                        senderConfiguration,
                        ontology
                );
        String ruleId = catalog.saveRule(
                new RuleRequest(
                        null,
                        "rule-auto-create-1",
                        worksite,
                        template,
                        sender,
                        null,
                        "Cobrança um dia antes",
                        "AUTOMATICA",
                        "VENCIMENTO",
                        -1,
                        LocalTime.of(9, 0),
                        "America/Sao_Paulo",
                        null,
                        null
                ),
                actor
        ).id();
        catalog.previewRule(
                ruleId,
                worksite,
                new TargetReference("LANCAMENTO", ledger),
                actor
        );
        catalog.activateRule(ruleId, worksite, actor);
        FinanceAutomaticChargeService automatic =
                new FinanceAutomaticChargeService(
                        jdbc,
                        objectMapper,
                        targetReader,
                        new FinanceChargeOccurrenceCalculator(),
                        senderConfiguration,
                        ontology
                );

        assertThat(automatic.generate(25)).isEqualTo(1);
        assertThat(automatic.generate(25)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_cobranca_email WHERE regra_id = ?",
                Integer.class,
                ruleId
        )).isEqualTo(1);
    }

    private String collaborator(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO colaborador (id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso, ativo) VALUES (?, 'teste', 'colaborador', ?, 'Gestora', 'ALFA', 1)",
                id,
                id
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra de cobranças')",
                id,
                "CHARGE-" + id
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
                "INSERT INTO finance_fornecedor (id, razao_social, cnpj_normalizado, email_financeiro, criado_por) VALUES (?, 'Fornecedor Exemplo', ?, 'financeiro@example.com', ?)",
                id,
                String.format("%014d", Math.abs(id.hashCode())),
                actor
        );
        jdbc.update(
                "INSERT INTO finance_fornecedor_obra (id, fornecedor_id, obra_id, criado_por) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                id,
                worksite,
                actor
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
                status,
                worksite,
                actor
        );
        jdbc.update(
                """
                INSERT INTO finance_lancamento (
                    id, client_mutation_id, obra_id, fornecedor_id,
                    responsavel_id, status_id, tipo, origem,
                    numero_documento, descricao, data_competencia,
                    vencimento_em, moeda, valor_original, valor_liquido,
                    criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, 'RECEBER', 'MANUAL', 'DOC-42',
                          'Medição real', ?, ?, 'BRL', 125.50, 125.50, ?)
                """,
                ledger,
                "ledger-" + ledger,
                worksite,
                supplier,
                actor,
                status,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 20),
                actor
        );
        return ledger;
    }

    private String sender(JdbcTemplate jdbc, String actor) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO finance_email_perfil_remetente (id, codigo, nome, configuracao_remetente_chave, criado_por) VALUES (?, 'FINANCEIRO', 'Financeiro Stavias', ?, ?)",
                id,
                SENDER_KEY,
                actor
        );
        return id;
    }

    private String template(
            JdbcTemplate jdbc,
            String actor,
            String worksite
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_modelo_email (
                    id, obra_id, codigo, nome, versao, status,
                    assunto_template, corpo_texto_template,
                    variaveis_permitidas_json, previsualizacao_hash,
                    previsualizado_por, previsualizado_em, criado_por
                ) VALUES (?, ?, 'COBRANCA', 'Cobrança', 1, 'ATIVO',
                          'Cobrança {{numeroDocumento}} — {{obraNome}}',
                          'Olá, {{fornecedorNome}}. Aberto: {{valorAberto}}.',
                          JSON_ARRAY('numeroDocumento', 'obraNome',
                                     'fornecedorNome', 'valorAberto'),
                          ?, ?, CURRENT_TIMESTAMP(6), ?)
                """,
                id,
                worksite,
                "a".repeat(64),
                actor,
                actor
        );
        return id;
    }
}

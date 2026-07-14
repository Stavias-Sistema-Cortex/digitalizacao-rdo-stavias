package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceEmailChargesMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V33__email_charges_and_ontology_extensions.sql"
    );

    @Test
    void definesConfiguredSenderTemplateRuleChargeAttemptAndDeliveryModel()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE finance_email_perfil_remetente")
                .contains("CREATE TABLE finance_email_reply_to_permitido")
                .contains("CREATE TABLE finance_modelo_email")
                .contains("CREATE TABLE finance_regra_cobranca")
                .contains("CREATE TABLE finance_cobranca_email")
                .contains("CREATE TABLE finance_cobranca_tentativa")
                .contains("CREATE TABLE finance_cobranca_evento_entrega")
                .contains("CREATE TABLE finance_cobranca_email_historico")
                .contains("configuracao_remetente_chave")
                .contains("previsualizacao_hash")
                .contains("idempotency_key")
                .contains("provider_message_id")
                .contains("erro_categoria")
                .contains("proxima_tentativa_em");
    }

    @Test
    void enforcesRealModesPreviewActivationAndTraceableTargetLinks()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("modo IN ('MANUAL', 'AGENDADA', 'AUTOMATICA')")
                .contains("status IN (\n            'RASCUNHO', 'PREVISUALIZADA', 'AGENDADA', 'NA_FILA',")
                .contains("previsualizacao_confirmada_por")
                .contains("previsualizacao_confirmada_em")
                .contains("REFERENCES finance_compra(id, obra_id)")
                .contains("REFERENCES finance_nota_fiscal(id, obra_id)")
                .contains("REFERENCES finance_lancamento(id, obra_id)")
                .contains("UNIQUE (regra_id, alvo_tipo, alvo_id, ocorrencia_prevista_em)")
                .contains("UNIQUE (cobranca_id, numero_tentativa)")
                .contains("UNIQUE (provider, provider_event_id)");
    }

    @Test
    void storesNoProviderCredentialsOrRenderedMessageBodyInDeliveryRecords()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .doesNotContainIgnoringCase("smtp_password")
                .doesNotContainIgnoringCase("client_secret")
                .doesNotContainIgnoringCase("access_token")
                .doesNotContainIgnoringCase("refresh_token")
                .doesNotContain("corpo_renderizado")
                .doesNotContain("mensagem_renderizada")
                .doesNotContain("LONGBLOB")
                .doesNotContain("conteudo_base64");
    }
}

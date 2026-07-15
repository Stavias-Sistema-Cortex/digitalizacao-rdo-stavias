package com.projeto.cortex.mensagens;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingMigrationTest {

    @Test
    void v28ShouldCreateScopedIdempotentMessagingDomain() throws Exception {
        Path migration = Path.of(
                "src/main/resources/db/migration/V28__create_messaging.sql"
        );
        assertThat(migration).exists();
        String sql = Files.readString(migration);

        assertThat(sql).contains("CREATE TABLE conversa");
        assertThat(sql).contains("CREATE TABLE conversa_participante");
        assertThat(sql).contains("CREATE TABLE mensagem");
        assertThat(sql).contains("CREATE TABLE mensagem_referencia");
        assertThat(sql).contains("CREATE TABLE mensagem_anexo");
        assertThat(sql).contains("CREATE TABLE mensagem_recibo");

        assertThat(sql).contains("versao_linha BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("client_message_id CHAR(36) NOT NULL");
        assertThat(sql).contains("UNIQUE (remetente_id, client_message_id)");
        assertThat(sql).contains("enviada_cliente_em");
        assertThat(sql).contains("criada_servidor_em");
        assertThat(sql).contains("participante_ativo_id CHAR(36) GENERATED ALWAYS AS");

        assertThat(sql).contains("REFERENCES obra(id)");
        assertThat(sql).contains("REFERENCES equipe(id)");
        assertThat(sql).contains("REFERENCES colaborador(id)");
        assertThat(sql).contains("REFERENCES conversa(id)");
        assertThat(sql).contains("REFERENCES mensagem(id)");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");

        assertThat(sql).contains("storage_key VARCHAR(255)");
        assertThat(sql).contains("hash_sha256 CHAR(64)");
        assertThat(sql).contains("client_attachment_id CHAR(36) NOT NULL");
        assertThat(sql).contains("UNIQUE (mensagem_id, client_attachment_id)");

        assertThat(sql).contains("DROP CHECK chk_sync_mutacao_operacao");
        assertThat(sql).contains("'CRIAR_MENSAGEM'");
        assertThat(sql).doesNotContain("INSERT INTO conversa");
        assertThat(sql).doesNotContain("INSERT INTO mensagem");
    }
}

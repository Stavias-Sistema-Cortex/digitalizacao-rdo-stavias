package com.projeto.cortex.mensagens;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MessagingMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V30__messaging_teams.sql"
    );

    @Test
    void definesNormalizedMessagingMetadataWithoutBinaryBodies() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE equipe")
                .contains("CREATE TABLE equipe_membro")
                .contains("CREATE TABLE conversa")
                .contains("CREATE TABLE conversa_participante")
                .contains("CREATE TABLE mensagem")
                .contains("CREATE TABLE mensagem_anexo")
                .contains("chave_participantes_direta")
                .contains("client_mutation_id")
                .contains("stored_object_id")
                .contains("versao_linha")
                .contains("deletado_em")
                .contains("FULLTEXT INDEX ft_mensagem_corpo")
                .contains("idx_mensagem_conversa_criado")
                .doesNotContain("LONGBLOB")
                .doesNotContain("MEDIUMBLOB")
                .doesNotContain("conteudo_base64")
                .doesNotContain("arquivo_base64");
    }

    @Test
    void constrainsConversationScopesMembershipAndIdempotency() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CHECK (tipo IN ('DIRETA', 'GRUPO', 'EQUIPE', 'OBRA'))")
                .contains("UNIQUE (chave_participantes_direta)")
                .contains("UNIQUE (equipe_id, colaborador_id)")
                .contains("UNIQUE (conversa_id, colaborador_id)")
                .contains("UNIQUE (autor_id, client_mutation_id)")
                .contains("FOREIGN KEY (obra_id) REFERENCES obra(id)")
                .contains("FOREIGN KEY (stored_object_id) REFERENCES stored_object(id)")
                .contains("CHECK (status IN ('ATIVO', 'REMOVIDO'))")
                .contains("CHECK (status IN ('ATIVA', 'EDITADA', 'EXCLUIDA'))");
    }
}

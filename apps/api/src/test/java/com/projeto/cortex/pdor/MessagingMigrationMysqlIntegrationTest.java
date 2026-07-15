package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class MessagingMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void migratesV1ThroughV30AndEnforcesMessagingRelationships() {
        database = PdorMysqlTestDatabase.create("messaging_v30");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        String alphaId = collaborator(jdbc, "Alfa");
        String betaId = collaborator(jdbc, "Beta");
        String obraId = worksite(jdbc);
        String teamId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String objectId = storedObject(jdbc, alphaId, obraId);

        jdbc.update(
                """
                INSERT INTO equipe (id, obra_id, nome, criado_por)
                VALUES (?, ?, 'Campo', ?)
                """,
                teamId,
                obraId,
                alphaId
        );
        jdbc.update(
                """
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, papel, adicionado_por
                ) VALUES (?, ?, ?, 'MEMBRO', ?)
                """,
                UUID.randomUUID().toString(),
                teamId,
                betaId,
                alphaId
        );
        jdbc.update(
                """
                INSERT INTO conversa (
                    id, tipo, obra_id, equipe_id, titulo, criado_por
                ) VALUES (?, 'EQUIPE', ?, ?, 'Equipe Campo', ?)
                """,
                conversationId,
                obraId,
                teamId,
                alphaId
        );
        jdbc.update(
                """
                INSERT INTO conversa_participante (
                    id, conversa_id, colaborador_id, papel, adicionado_por
                ) VALUES (?, ?, ?, 'MEMBRO', ?)
                """,
                UUID.randomUUID().toString(),
                conversationId,
                betaId,
                alphaId
        );
        jdbc.update(
                """
                INSERT INTO mensagem (
                    id, conversa_id, autor_id, corpo, client_mutation_id,
                    criado_cliente_em
                ) VALUES (?, ?, ?, 'Mensagem real', 'client-message-1',
                          CURRENT_TIMESTAMP(6))
                """,
                messageId,
                conversationId,
                betaId
        );
        assertThat(jdbc.update(
                """
                INSERT INTO mensagem_anexo (
                    id, mensagem_id, stored_object_id, nome_exibicao,
                    criado_por
                ) VALUES (?, ?, ?, 'comprovante.txt', ?)
                """,
                UUID.randomUUID().toString(),
                messageId,
                objectId,
                betaId
        )).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO mensagem (
                    id, conversa_id, autor_id, corpo, client_mutation_id,
                    criado_cliente_em
                ) VALUES (?, ?, ?, 'Repetida', 'client-message-1',
                          CURRENT_TIMESTAMP(6))
                """,
                UUID.randomUUID().toString(),
                conversationId,
                betaId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsInvalidDirectConversationShapeAndDuplicateParticipantSet() {
        database = PdorMysqlTestDatabase.create("messaging_direct_v30");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String creatorId = collaborator(jdbc, "Criador");
        String directKey = "d".repeat(64);

        jdbc.update(
                """
                INSERT INTO conversa (
                    id, tipo, chave_participantes_direta, criado_por
                ) VALUES (?, 'DIRETA', ?, ?)
                """,
                UUID.randomUUID().toString(),
                directKey,
                creatorId
        );

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO conversa (
                    id, tipo, chave_participantes_direta, criado_por
                ) VALUES (?, 'DIRETA', ?, ?)
                """,
                UUID.randomUUID().toString(),
                directKey,
                creatorId
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO conversa (id, tipo, criado_por)
                VALUES (?, 'DIRETA', ?)
                """,
                UUID.randomUUID().toString(),
                creatorId
        )).isInstanceOf(DataAccessException.class);
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

    private String worksite(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                id,
                "CONTRATO-" + id,
                "Obra Mensagens"
        );
        return id;
    }

    private String storedObject(
            JdbcTemplate jdbc,
            String ownerId,
            String obraId
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, proprietario_id, obra_id, dedupe_key, sha256,
                    storage_backend, storage_key, media_type_detectado,
                    tamanho_bytes, criado_por, status
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL', ?, 'text/plain', 3, ?,
                          'DISPONIVEL')
                """,
                id,
                ownerId,
                obraId,
                "a".repeat(64),
                "b".repeat(64),
                "objects/bb/" + id,
                ownerId
        );
        return id;
    }
}

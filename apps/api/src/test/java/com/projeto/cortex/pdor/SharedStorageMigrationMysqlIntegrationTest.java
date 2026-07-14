package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class SharedStorageMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void migratesRealMysqlAndKeepsOneExtensibleSyncReceiptProtocol() {
        database = PdorMysqlTestDatabase.create("shared_storage_v29");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        String ownerId = UUID.randomUUID().toString();
        String obraId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, ativo
                ) VALUES (?, 'teste', 'colaborador', ?, 'Usuário Teste', 1)
                """,
                ownerId,
                ownerId
        );
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "CONTRATO-" + obraId,
                "Obra Teste"
        );
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, proprietario_id, obra_id, dedupe_key, sha256,
                    storage_backend, storage_key, media_type_detectado,
                    tamanho_bytes, criado_por
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL', ?, 'text/plain', 3, ?)
                """,
                UUID.randomUUID().toString(),
                ownerId,
                obraId,
                "a".repeat(64),
                "b".repeat(64),
                "objects/bb/id/hash",
                ownerId
        );

        jdbc.update(
                """
                INSERT INTO sync_dispositivo (id, usuario_id, ativo)
                VALUES (?, ?, 1)
                """,
                deviceId,
                ownerId
        );
        assertThat(jdbc.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id, dispositivo_id, client_mutation_id,
                    entidade_tipo, operacao, payload_json
                ) VALUES (?, ?, 'client-1', 'MENSAGEM',
                          'CRIAR_MENSAGEM', JSON_OBJECT())
                """,
                UUID.randomUUID().toString(),
                deviceId
        )).isEqualTo(1);

        jdbc.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, tipo_evento,
                    usuario_id, dispositivo_id, payload_json
                ) VALUES (?, 1, 'MENSAGEM', ?, 'MENSAGEM_CRIADA', ?, ?,
                          JSON_OBJECT())
                """,
                eventId,
                UUID.randomUUID().toString(),
                ownerId,
                deviceId
        );
        assertThat(jdbc.update(
                """
                INSERT INTO cortex_evento_visibilidade (
                    id, evento_id, escopo_tipo, escopo_id
                ) VALUES (?, ?, 'WORKSITE', ?)
                """,
                UUID.randomUUID().toString(),
                eventId,
                obraId
        )).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'sync_mutation_receipt'
                """,
                Integer.class
        )).isZero();
    }
}

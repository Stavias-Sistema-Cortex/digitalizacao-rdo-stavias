package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "CORTEX_MYSQL_ROOT_PASSWORD", matches = ".+")
class AuthSecurityMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void migratesV26DataWithoutRegrantingAlfaOrRetainingCpfProjections() {
        database = PdorMysqlTestDatabase.create("auth_v27_data");
        migrateToV26();

        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String alfaId = UUID.randomUUID().toString();
        String nullRoleId = UUID.randomUUID().toString();
        String invalidRoleId = UUID.randomUUID().toString();
        String objectId = UUID.randomUUID().toString();
        String legacyCpfHash = "ab".repeat(32);

        insertColaborador(jdbc, alfaId, "alfa-sintetico", legacyCpfHash, "ALFA");
        insertColaborador(jdbc, nullRoleId, "sem-papel-sintetico", null, null);
        insertColaborador(jdbc, invalidRoleId, "papel-invalido-sintetico", null, "GAMA");
        insertOperationalProjections(jdbc, objectId, alfaId, legacyCpfHash);

        database.migrate();

        assertThat(role(jdbc, alfaId)).isEqualTo("ALFA");
        assertThat(role(jdbc, nullRoleId)).isEqualTo("BETA");
        assertThat(role(jdbc, invalidRoleId)).isEqualTo("BETA");
        assertThat(jdbc.queryForObject(
                "SELECT cpf_hash FROM colaborador WHERE id = ?",
                String.class,
                alfaId
        )).isEqualTo(legacyCpfHash);

        assertThat(countEvidence(jdbc, "cpf_hash")).isZero();
        assertThat(countEvidence(jdbc, "nome")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT JSON_CONTAINS_PATH(snapshot_origem_json, 'one', '$.cpf_hash')
                FROM cortex_mapeamento_legado
                WHERE entidade_id = ?
                """,
                Integer.class,
                alfaId
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot_origem_json, '$.nome'))
                FROM cortex_mapeamento_legado
                WHERE entidade_id = ?
                """,
                String.class,
                alfaId
        )).isEqualTo("Colaborador Sintético");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE colaborador SET papel_acesso = 'GAMA' WHERE id = ?",
                nullRoleId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_colaborador_papel_acesso");
    }

    private void migrateToV26() {
        Flyway.configure()
                .dataSource(
                        database.jdbcUrl(),
                        "root",
                        PdorMysqlTestDatabase.rootPassword()
                )
                .locations("filesystem:src/main/resources/db/migration")
                .target(MigrationVersion.fromVersion("26"))
                .load()
                .migrate();
    }

    private void insertColaborador(
            JdbcTemplate jdbc,
            String id,
            String sourceId,
            String cpfHash,
            String papelAcesso
    ) {
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    cpf_hash,
                    nome,
                    papel_acesso,
                    ativo
                ) VALUES (?, 'teste', 'teste', ?, ?, 'Colaborador Sintético', ?, 1)
                """,
                id,
                sourceId,
                cpfHash,
                papelAcesso
        );
    }

    private void insertOperationalProjections(
            JdbcTemplate jdbc,
            String objectId,
            String colaboradorId,
            String cpfHash
    ) {
        jdbc.update(
                """
                INSERT INTO cortex_objeto (
                    id, tipo_entidade, entidade_id, nome, fonte, chave_canonica
                ) VALUES (?, 'COLABORADOR', ?, 'Colaborador Sintético',
                          'TESTE', 'colaborador-sintetico')
                """,
                objectId,
                colaboradorId
        );
        jdbc.update(
                """
                INSERT INTO cortex_evidencia_operacional (
                    id, objeto_id, tipo_entidade, entidade_id,
                    nome_campo, valor_campo, fonte
                ) VALUES (?, ?, 'COLABORADOR', ?, 'cpf_hash', ?, 'TESTE')
                """,
                UUID.randomUUID().toString(),
                objectId,
                colaboradorId,
                cpfHash
        );
        jdbc.update(
                """
                INSERT INTO cortex_evidencia_operacional (
                    id, objeto_id, tipo_entidade, entidade_id,
                    nome_campo, valor_campo, fonte
                ) VALUES (?, ?, 'COLABORADOR', ?, 'nome',
                          'Colaborador Sintético', 'TESTE')
                """,
                UUID.randomUUID().toString(),
                objectId,
                colaboradorId
        );
        jdbc.update(
                """
                INSERT INTO cortex_mapeamento_legado (
                    id, sistema_legado, tabela_legado, id_legado,
                    objeto_id, tipo_entidade, entidade_id, snapshot_origem_json
                ) VALUES (?, 'teste', 'colaborador', 'registro-sintetico', ?,
                          'COLABORADOR', ?, JSON_OBJECT(
                              'cpf_hash', ?,
                              'nome', 'Colaborador Sintético'
                          ))
                """,
                UUID.randomUUID().toString(),
                objectId,
                colaboradorId,
                cpfHash
        );
    }

    private String role(JdbcTemplate jdbc, String colaboradorId) {
        return jdbc.queryForObject(
                "SELECT papel_acesso FROM colaborador WHERE id = ?",
                String.class,
                colaboradorId
        );
    }

    private int countEvidence(JdbcTemplate jdbc, String fieldName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_evidencia_operacional WHERE nome_campo = ?",
                Integer.class,
                fieldName
        );
        return count == null ? 0 : count;
    }
}

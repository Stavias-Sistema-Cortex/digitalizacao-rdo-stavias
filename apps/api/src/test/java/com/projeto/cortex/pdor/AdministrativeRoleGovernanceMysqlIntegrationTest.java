package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class AdministrativeRoleGovernanceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void bootstrapsOnlyExistingActiveAlfasAndKeepsHistoryAppendOnly() {
        database = PdorMysqlTestDatabase.create("auth_roles_v39");
        migrateToV38();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String alfaOne = collaborator(jdbc, "Alfa um", "ALFA", true);
        String alfaTwo = collaborator(jdbc, "Alfa dois", "ALFA", true);
        String beta = collaborator(jdbc, "Beta", "BETA", true);
        collaborator(jdbc, "Alfa inativa", "ALFA", false);

        database.migrate();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auth_capacidade_administrativa
                WHERE capacidade = 'ADMINISTRAR_PAPEIS'
                  AND ativa = 1
                """, Integer.class)).isEqualTo(2);
        assertThat(hasCapability(jdbc, alfaOne)).isTrue();
        assertThat(hasCapability(jdbc, alfaTwo)).isTrue();
        assertThat(hasCapability(jdbc, beta)).isFalse();

        assertThat(jdbc.update("""
                INSERT INTO auth_papel_acesso_historico (
                    id, colaborador_id, papel_anterior, papel_novo,
                    alterado_por, justificativa
                ) VALUES (?, ?, 'BETA', 'ALFA', ?, 'Promoção autorizada')
                """, UUID.randomUUID().toString(), beta, alfaOne)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_papel_acesso_historico",
                Integer.class
        )).isEqualTo(1);
    }

    private void migrateToV38() {
        Flyway.configure()
                .dataSource(
                        database.jdbcUrl(),
                        "root",
                        PdorMysqlTestDatabase.rootPassword()
                )
                .locations("filesystem:src/main/resources/db/migration")
                .target(MigrationVersion.fromVersion("38"))
                .load()
                .migrate();
    }

    private String collaborator(
            JdbcTemplate jdbc,
            String name,
            String role,
            boolean active
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, papel_acesso, ativo
                ) VALUES (?, 'teste', 'colaborador', ?, ?, ?, ?)
                """, id, id, name, role, active);
        return id;
    }

    private boolean hasCapability(JdbcTemplate jdbc, String collaboratorId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auth_capacidade_administrativa
                WHERE colaborador_id = ?
                  AND capacidade = 'ADMINISTRAR_PAPEIS'
                  AND ativa = 1
                """, Integer.class, collaboratorId);
        return count != null && count == 1;
    }
}

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

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinanceControlUnitsMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void backfillsEveryWorksiteAndMovesExistingGrantToItsUnit() {
        database = PdorMysqlTestDatabase.create("finance_units_v34");
        migrateToV33();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        String actorId = collaborator(jdbc, "Alfa Financeiro", "ALFA");
        String betaId = collaborator(jdbc, "Beta Financeiro", "BETA");
        String activeWorksiteId = worksite(jdbc, "Obra ativa", false);
        String archivedWorksiteId = worksite(jdbc, "Obra arquivada", true);
        jdbc.update(
                """
                INSERT INTO permissao_financeira_colaborador (
                    id, colaborador_id, obra_id, permissao, concedido_por,
                    justificativa
                ) VALUES (?, ?, ?, 'FINANCEIRO_VISUALIZAR', ?,
                          'Acesso legado explicitamente concedido.')
                """,
                UUID.randomUUID().toString(),
                betaId,
                activeWorksiteId,
                actorId
        );

        database.migrate();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance_unidade_controle",
                Long.class
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT status FROM finance_unidade_controle
                WHERE obra_id = ?
                """,
                String.class,
                archivedWorksiteId
        )).isEqualTo("ARQUIVADA");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM permissao_financeira_colaborador p
                JOIN finance_unidade_controle u
                  ON u.id = p.unidade_controle_id
                 AND u.obra_id = p.obra_id
                WHERE p.colaborador_id = ?
                """,
                Long.class,
                betaId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM permissao_financeira_colaborador
                WHERE unidade_controle_id IS NULL
                """,
                Long.class
        )).isZero();
    }

    @Test
    void enforcesSingleAllocationSourceAndExactAssetUnitIdentity() {
        database = PdorMysqlTestDatabase.create("finance_integrity_v34");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        String actorId = collaborator(jdbc, "Gestor Financeiro", "ALFA");
        String purchaseItemId = purchaseItem(jdbc, actorId);
        String assetA = asset(jdbc, "ATIVO-A");
        String assetB = asset(jdbc, "ATIVO-B");
        String unitA = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, ativo_id, codigo, nome, origem, criado_por
                ) VALUES (?, 'ATIVO', ?, 'ATIVO:A', 'Ativo A',
                          'COMPRA_ATIVO', ?)
                """,
                unitA,
                assetA,
                actorId
        );

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, ativo_id, codigo, nome, origem, criado_por
                ) VALUES (?, 'ATIVO', ?, 'ATIVO:A:2', 'Ativo duplicado',
                          'COMPRA_ATIVO', ?)
                """,
                UUID.randomUUID().toString(),
                assetA,
                actorId
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, ativo_id, codigo, nome, origem, criado_por
                ) VALUES (?, 'ATIVO', NULL, 'ATIVO:INVALIDO',
                          'Sem ativo', 'COMPRA_ATIVO', ?)
                """,
                UUID.randomUUID().toString(),
                actorId
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_rateio (
                    id, client_mutation_id, compra_id, nota_fiscal_id,
                    lancamento_id, moeda, valor_total, criado_por
                ) VALUES (?, 'sem-origem', NULL, NULL, NULL,
                          'BRL', 10.0000, ?)
                """,
                UUID.randomUUID().toString(),
                actorId
        )).isInstanceOf(DataAccessException.class);

        String otherUnit = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, ativo_id, codigo, nome, origem, criado_por
                ) VALUES (?, 'ATIVO', ?, 'ATIVO:B', 'Ativo B',
                          'COMPRA_ATIVO', ?)
                """,
                otherUnit,
                assetB,
                actorId
        );
        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_compra_item_ativo (
                    id, compra_item_id, ativo_id, unidade_controle_id,
                    sequencia, criado_por
                ) VALUES (?, ?, ?, ?, 1, ?)
                """,
                UUID.randomUUID().toString(),
                purchaseItemId,
                assetB,
                unitA,
                actorId
        )).isInstanceOf(DataAccessException.class);

        assertThat(jdbc.update(
                """
                INSERT INTO finance_compra_item_ativo (
                    id, compra_item_id, ativo_id, unidade_controle_id,
                    sequencia, criado_por
                ) VALUES (?, ?, ?, ?, 1, ?)
                """,
                UUID.randomUUID().toString(),
                purchaseItemId,
                assetA,
                unitA,
                actorId
        )).isEqualTo(1);
    }

    private void migrateToV33() {
        Flyway.configure()
                .dataSource(
                        database.jdbcUrl(),
                        "root",
                        PdorMysqlTestDatabase.rootPassword()
                )
                .locations("filesystem:src/main/resources/db/migration")
                .target(MigrationVersion.fromVersion("33"))
                .load()
                .migrate();
    }

    private String collaborator(
            JdbcTemplate jdbc,
            String name,
            String role
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    ativo, papel_acesso
                ) VALUES (?, 'teste', 'colaborador', ?, ?, 1, ?)
                """,
                id,
                id,
                name,
                role
        );
        return id;
    }

    private String worksite(
            JdbcTemplate jdbc,
            String name,
            boolean archived
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, nome, status, arquivado_em
                ) VALUES (?, ?, ?, ?,
                          CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE NULL END)
                """,
                id,
                "FIN-" + id,
                name,
                archived ? "ARQUIVADA" : "ATIVA",
                archived
        );
        return id;
    }

    private String asset(JdbcTemplate jdbc, String code) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO asset (
                    id, source_database, source_table, source_pk,
                    external_code, name
                ) VALUES (?, 'teste', 'asset', ?, ?, ?)
                """,
                id,
                id,
                code,
                code
        );
        return id;
    }

    private String purchaseItem(JdbcTemplate jdbc, String actorId) {
        String obraId = worksite(jdbc, "Obra da compra", false);
        String supplierId = UUID.randomUUID().toString();
        String centerId = UUID.randomUUID().toString();
        String categoryId = UUID.randomUUID().toString();
        String statusId = UUID.randomUUID().toString();
        String purchaseId = UUID.randomUUID().toString();
        String itemId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_fornecedor (
                    id, razao_social, cnpj_normalizado, criado_por
                ) VALUES (?, 'Fornecedor de ativo', '12345678000195', ?)
                """,
                supplierId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_fornecedor_obra (
                    id, fornecedor_id, obra_id, criado_por
                ) VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                supplierId,
                obraId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_centro_custo (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'ATIVO', 'Aquisição de ativo', ?)
                """,
                centerId,
                obraId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_categoria (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'EQUIP', 'Equipamento', ?)
                """,
                categoryId,
                obraId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome, ordem,
                    terminal, criado_por
                ) VALUES (?, ?, 'COMPRA', 'RECEBIDA', 'Recebida', 1, 1, ?)
                """,
                statusId,
                obraId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, fornecedor_id, responsavel_compra_id,
                    status_id, descricao, prioridade, moeda,
                    valor_contratado, criado_por
                ) VALUES (?, 'compra-ativo-v34', ?, ?, ?, ?, ?, ?,
                          'Aquisição explícita', 'MEDIA', 'BRL', 100.0000, ?)
                """,
                purchaseId,
                obraId,
                centerId,
                categoryId,
                supplierId,
                actorId,
                statusId,
                actorId
        );
        jdbc.update(
                """
                INSERT INTO finance_compra_item (
                    id, compra_id, ordem, descricao, quantidade, unidade,
                    valor_unitario, valor_total, natureza, criado_por
                ) VALUES (?, ?, 1, 'Equipamento', 1.0000, 'UN',
                          100.0000, 100.0000, 'CAPITALIZAVEL', ?)
                """,
                itemId,
                purchaseId,
                actorId
        );
        return itemId;
    }
}

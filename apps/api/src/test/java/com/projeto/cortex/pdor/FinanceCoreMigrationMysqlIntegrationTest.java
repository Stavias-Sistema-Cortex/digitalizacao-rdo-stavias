package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
class FinanceCoreMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void migratesV1ThroughV31AndEnforcesConfigurablePurchaseReferences() {
        database = PdorMysqlTestDatabase.create("finance_v31");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        String alphaId = collaborator(jdbc, "Alfa Financeiro");
        String obraId = worksite(jdbc);
        String supplierId = UUID.randomUUID().toString();
        String centerId = UUID.randomUUID().toString();
        String categoryId = UUID.randomUUID().toString();
        String draftStatusId = UUID.randomUUID().toString();
        String requestedStatusId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();

        jdbc.update(
                """
                INSERT INTO finance_fornecedor (
                    id, razao_social, cnpj_normalizado, email_financeiro,
                    criado_por
                ) VALUES (?, 'Fornecedor Real', '12345678000195',
                          'financeiro@example.test', ?)
                """,
                supplierId,
                alphaId
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
                alphaId
        );
        jdbc.update(
                """
                INSERT INTO finance_centro_custo (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'CC-01', 'Pavimentação', ?)
                """,
                centerId,
                obraId,
                alphaId
        );
        jdbc.update(
                """
                INSERT INTO finance_categoria (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'MAT', 'Materiais', ?)
                """,
                categoryId,
                obraId,
                alphaId
        );
        insertStatus(jdbc, draftStatusId, obraId, "RASCUNHO", alphaId);
        insertStatus(jdbc, requestedStatusId, obraId, "SOLICITADA", alphaId);
        jdbc.update(
                """
                INSERT INTO finance_status_transicao (
                    id, obra_id, agregado_tipo, status_origem_id,
                    status_destino_id, criado_por
                ) VALUES (?, ?, 'SOLICITACAO', ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                obraId,
                draftStatusId,
                requestedStatusId,
                alphaId
        );
        assertThat(jdbc.update(
                """
                INSERT INTO finance_solicitacao_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, fornecedor_id, solicitante_id,
                    responsavel_compra_id, status_id, titulo, prioridade,
                    moeda, valor_previsto, criado_por
                ) VALUES (?, 'client-request-1', ?, ?, ?, ?, ?, ?, ?,
                          'Brita graduada', 'ALTA', 'BRL', 1000.5000, ?)
                """,
                requestId,
                obraId,
                centerId,
                categoryId,
                supplierId,
                alphaId,
                alphaId,
                draftStatusId,
                alphaId
        )).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_solicitacao_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, solicitante_id, status_id, titulo,
                    prioridade, moeda, valor_previsto, criado_por
                ) VALUES (?, 'client-request-1', ?, ?, ?, ?, ?, 'Duplicada',
                          'ALTA', 'BRL', 1.0000, ?)
                """,
                UUID.randomUUID().toString(),
                obraId,
                centerId,
                categoryId,
                alphaId,
                draftStatusId,
                alphaId
        )).isInstanceOf(DataAccessException.class);

        BigDecimal stored = jdbc.queryForObject(
                "SELECT valor_previsto FROM finance_solicitacao_compra WHERE id = ?",
                BigDecimal.class,
                requestId
        );
        assertThat(stored).isEqualByComparingTo("1000.5000");
    }

    @Test
    void rejectsInvalidMoneyRangeAndCrossWorksiteCostCenter() {
        database = PdorMysqlTestDatabase.create("finance_scope_v31");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String actorId = collaborator(jdbc, "Gestor");
        String obraA = worksite(jdbc);
        String obraB = worksite(jdbc);
        String centerA = UUID.randomUUID().toString();
        String centerB = UUID.randomUUID().toString();
        String categoryB = UUID.randomUUID().toString();
        String statusB = UUID.randomUUID().toString();

        jdbc.update(
                "INSERT INTO finance_centro_custo (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, 'A', 'Centro A', ?)",
                centerA,
                obraA,
                actorId
        );
        jdbc.update(
                "INSERT INTO finance_centro_custo (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, 'B', 'Centro B', ?)",
                centerB,
                obraB,
                actorId
        );
        jdbc.update(
                "INSERT INTO finance_categoria (id, obra_id, codigo, nome, criado_por) VALUES (?, ?, 'B', 'Categoria B', ?)",
                categoryB,
                obraB,
                actorId
        );
        insertStatus(jdbc, statusB, obraB, "RASCUNHO", actorId);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_solicitacao_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, solicitante_id, status_id, titulo,
                    prioridade, moeda, valor_previsto, criado_por
                ) VALUES (?, 'cross-scope', ?, ?, ?, ?, ?, 'Inválida',
                          'MEDIA', 'BRL', 1.0000, ?)
                """,
                UUID.randomUUID().toString(),
                obraB,
                centerA,
                categoryB,
                actorId,
                statusB,
                actorId
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO finance_solicitacao_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, solicitante_id, status_id, titulo,
                    prioridade, moeda, valor_previsto, criado_por
                ) VALUES (?, 'negative-money', ?, ?, ?, ?, ?, 'Inválida',
                          'MEDIA', 'BRL', -1.0000, ?)
                """,
                UUID.randomUUID().toString(),
                obraB,
                centerB,
                categoryB,
                actorId,
                statusB,
                actorId
        )).isInstanceOf(DataAccessException.class);
    }

    private void insertStatus(
            JdbcTemplate jdbc,
            String id,
            String obraId,
            String code,
            String actorId
    ) {
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome, ordem,
                    criado_por
                ) VALUES (?, ?, 'SOLICITACAO', ?, ?, 1, ?)
                """,
                id,
                obraId,
                code,
                code,
                actorId
        );
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
                "FIN-" + id,
                "Obra Financeira"
        );
        return id;
    }
}

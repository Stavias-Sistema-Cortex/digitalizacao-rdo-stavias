package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.projeto.cortex.financeiro.access.FinancialGrantRepository;
import com.projeto.cortex.financeiro.access.FinancialGrantResponse;
import com.projeto.cortex.financeiro.access.FinancialGrantService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinancialGrantServiceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void persistsAndRevokesCanonicalUnitGrantWithRealActors() {
        database = PdorMysqlTestDatabase.create("finance_unit_grant");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String alphaId = collaborator(jdbc, "Alfa", "ALFA");
        String betaId = collaborator(jdbc, "Beta", "BETA");
        String assetId = asset(jdbc);
        String unitId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, ativo_id, codigo, nome, origem,
                    criado_por, atualizado_por
                ) VALUES (?, 'ATIVO', ?, ?, 'Escavadeira',
                          'COMPRA_ATIVO', ?, ?)
                """,
                unitId,
                assetId,
                "ATIVO:" + assetId,
                alphaId,
                alphaId
        );

        FinancialGrantService service = new FinancialGrantService(
                new FinancialGrantRepository(jdbc),
                new FinancialUnitRepository(jdbc),
                mock(CortexOperationalMemoryService.class),
                mock(ObraOperabilityGuard.class)
        );
        FinancialGrantResponse granted = service.grantUnit(
                unitId,
                betaId,
                FinancialPermission.FINANCEIRO_OPERAR,
                "Responsável financeiro pelo equipamento.",
                alphaId
        );

        Map<String, Object> stored = jdbc.queryForMap(
                """
                SELECT unidade_controle_id, obra_id, concedido_por, status
                FROM permissao_financeira_colaborador
                WHERE id = ?
                """,
                granted.id()
        );
        assertThat(stored.get("unidade_controle_id")).isEqualTo(unitId);
        assertThat(stored.get("obra_id")).isNull();
        assertThat(stored.get("concedido_por")).isEqualTo(alphaId);
        assertThat(stored.get("status")).isEqualTo("ATIVA");

        FinancialGrantResponse revoked = service.revokeUnit(
                unitId,
                betaId,
                FinancialPermission.FINANCEIRO_OPERAR,
                "Responsabilidade transferida para outro colaborador.",
                alphaId
        );
        assertThat(revoked.status()).isEqualTo("REVOGADA");
        assertThat(jdbc.queryForObject(
                """
                SELECT revogado_por
                FROM permissao_financeira_colaborador
                WHERE id = ?
                """,
                String.class,
                granted.id()
        )).isEqualTo(alphaId);
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
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, ativo, papel_acesso
                ) VALUES (?, 'teste', 'colaborador', ?, ?, 1, ?)
                """,
                id,
                id,
                name,
                role
        );
        return id;
    }

    private String asset(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO asset (
                    id, source_database, source_table, source_pk,
                    external_code, name
                ) VALUES (?, 'teste', 'asset', ?, 'EQ-01', 'Escavadeira')
                """,
                id,
                id
        );
        return id;
    }
}

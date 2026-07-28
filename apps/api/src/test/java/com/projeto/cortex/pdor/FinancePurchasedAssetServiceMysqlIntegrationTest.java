package com.projeto.cortex.pdor;

import static com.projeto.cortex.financeiro.asset.FinancePurchasedAssetDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialGrantRepository;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.financeiro.unit.FinancialUnitService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class FinancePurchasedAssetServiceMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void confirmsIndividualAssetsWithUnitsHistoryOntologyAndRollback() {
        database = PdorMysqlTestDatabase.create("fin_assets");
        database.migrate();
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        Seed seed = seed(jdbc);
        authenticate(seed.actorId());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CurrentUserService currentUser = new CurrentUserService(
                jdbc, new MockEnvironment(), false
        );
        FinancialUnitRepository unitRepository = new FinancialUnitRepository(jdbc);
        FinancialAccessService access = new FinancialAccessService(
                currentUser,
                new FinancialGrantRepository(jdbc),
                unitRepository
        );
        CortexOperationalMemoryService memory = new CortexOperationalMemoryService(
                jdbc,
                mapper,
                mock(ApplicationEventPublisher.class)
        );
        FinanceOntologyProjector ontology = new FinanceOntologyProjector(memory);
        FinancialUnitService unitService = new FinancialUnitService(
                unitRepository,
                access,
                ontology
        );
        FinancePurchasedAssetRepository repository =
                new FinancePurchasedAssetRepository(jdbc);
        FinancePurchasedAssetService service = new FinancePurchasedAssetService(
                repository,
                unitService,
                access,
                ontology,
                mapper,
                mock(ObraOperabilityGuard.class)
        );
        FinanceAuditContext audit = new FinanceAuditContext(
                seed.actorId(),
                "asset-device-1",
                "asset-correlation-1",
                "ONLINE"
        );
        ConfirmPurchasedAssetsRequest request = new ConfirmPurchasedAssetsRequest(
                "asset-confirmation-1",
                0L,
                List.of(
                        new PurchasedAssetTarget(seed.existingAssetId(), null),
                        new PurchasedAssetTarget(
                                null,
                                new NewAssetRequest(
                                        "EQ-NOVO-2",
                                        "Rolo compactador novo",
                                        "Máquinas"
                                )
                        )
                ),
                "asset-correlation-1",
                "asset-device-1"
        );

        PurchasedAssetResponse created = transactions.execute(
                status -> service.confirm(
                        seed.purchaseId(), seed.itemId(), request, audit
                )
        );
        PurchasedAssetResponse replay = transactions.execute(
                status -> service.confirm(
                        seed.purchaseId(), seed.itemId(), request, audit
                )
        );

        assertThat(replay).isEqualTo(created);
        assertThat(created.natureza()).isEqualTo("CAPITALIZAVEL");
        assertThat(created.quantidade()).isEqualByComparingTo("2.0000");
        assertThat(created.versaoItem()).isEqualTo(1L);
        assertThat(created.ativos()).hasSize(2);
        assertThat(created.ativos())
                .extracting(PurchasedAssetLinkResponse::sequencia)
                .containsExactly(1, 2);
        assertThat(jdbc.queryForObject(
                "SELECT versao_linha FROM finance_compra_item WHERE id = ?",
                Long.class,
                seed.itemId()
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM finance_compra_item_ativo l
                JOIN finance_unidade_controle u
                  ON u.id = l.unidade_controle_id
                 AND u.ativo_id = l.ativo_id
                 AND u.tipo = 'ATIVO'
                WHERE l.compra_item_id = ?
                """,
                Long.class,
                seed.itemId()
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_compra_item_ativo_historico h
                JOIN finance_compra_item_ativo l ON l.id = h.vinculo_id
                WHERE l.compra_item_id = ?
                  AND h.client_mutation_id = 'asset-confirmation-1'
                  AND h.alterado_por = ?
                  AND h.correlacao_id = 'asset-correlation-1'
                  AND h.dispositivo_id = 'asset-device-1'
                """,
                Long.class,
                seed.itemId(),
                seed.actorId()
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_relacao
                WHERE origem_tipo = 'COMPRA_ITEM'
                  AND origem_id = ?
                  AND destino_tipo = 'ATIVO'
                  AND tipo_relacao = 'ORIGINOU_ATIVO'
                  AND ativa = 1
                """,
                Long.class,
                seed.itemId()
        )).isEqualTo(2L);
        Map<String, Object> newAsset = jdbc.queryForMap(
                """
                SELECT source_database, source_table, source_pk, name
                FROM asset WHERE external_code = 'EQ-NOVO-2'
                """
        );
        assertThat(newAsset.get("source_database"))
                .isEqualTo("CORTEX_FINANCEIRO");
        assertThat(newAsset.get("source_table"))
                .isEqualTo("finance_compra_item");
        assertThat(newAsset.get("source_pk"))
                .isEqualTo(seed.itemId() + ":2");
        assertThat(newAsset.get("name"))
                .isEqualTo("Rolo compactador novo");

        proveRollback(
                jdbc,
                transactions,
                repository,
                unitService,
                access,
                mapper,
                seed,
                audit
        );
    }

    private void proveRollback(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            FinancePurchasedAssetRepository repository,
            FinancialUnitService unitService,
            FinancialAccessService access,
            ObjectMapper mapper,
            Seed seed,
            FinanceAuditContext audit
    ) {
        FinanceOntologyProjector failing = mock(FinanceOntologyProjector.class);
        doThrow(new IllegalStateException("ontology unavailable"))
                .when(failing)
                .successWithRelations(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()
                );
        FinancePurchasedAssetService service = new FinancePurchasedAssetService(
                repository,
                unitService,
                access,
                failing,
                mapper,
                mock(ObraOperabilityGuard.class)
        );
        ConfirmPurchasedAssetsRequest request = new ConfirmPurchasedAssetsRequest(
                "asset-confirmation-rollback",
                0L,
                List.of(new PurchasedAssetTarget(
                        null,
                        new NewAssetRequest(
                                "EQ-ROLLBACK",
                                "Equipamento transitório",
                                "Máquinas"
                        )
                )),
                "asset-rollback",
                "asset-device-1"
        );

        assertThatThrownBy(() -> transactions.execute(status -> service.confirm(
                seed.purchaseId(),
                seed.rollbackItemId(),
                request,
                audit
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ontology unavailable");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM asset WHERE external_code = 'EQ-ROLLBACK'",
                Long.class
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_compra_item_ativo
                WHERE compra_item_id = ?
                """,
                Long.class,
                seed.rollbackItemId()
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_compra_item_ativo_mutacao
                WHERE client_mutation_id = 'asset-confirmation-rollback'
                """,
                Long.class
        )).isZero();
    }

    private Seed seed(JdbcTemplate jdbc) {
        String actorId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, papel_acesso, ativo
                ) VALUES (?, 'teste', 'colaborador', ?,
                          'Alfa Patrimônio', 'ALFA', 1)
                """,
                actorId,
                actorId
        );
        String worksiteId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                worksiteId,
                "ASSET-" + worksiteId,
                "Obra dos Ativos"
        );
        String supplierId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_fornecedor (
                    id, razao_social, cnpj_normalizado, criado_por
                ) VALUES (?, 'Fornecedor Ativos', '00000000000001', ?)
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
                UUID.randomUUID().toString(), supplierId, worksiteId, actorId
        );
        String costCenterId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_centro_custo (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'ATIVOS', 'Ativos', ?)
                """,
                costCenterId, worksiteId, actorId
        );
        String categoryId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_categoria (
                    id, obra_id, codigo, nome, criado_por
                ) VALUES (?, ?, 'ATIVOS', 'Ativos', ?)
                """,
                categoryId, worksiteId, actorId
        );
        String statusId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome,
                    ordem, criado_por
                ) VALUES (?, ?, 'COMPRA', 'ATIVA', 'Ativa', 1, ?)
                """,
                statusId, worksiteId, actorId
        );
        String purchaseId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_compra (
                    id, client_mutation_id, obra_id, centro_custo_id,
                    categoria_id, fornecedor_id, responsavel_compra_id,
                    status_id, descricao, prioridade, moeda,
                    valor_contratado, criado_por
                ) VALUES (?, 'purchase-assets', ?, ?, ?, ?, ?, ?,
                          'Equipamentos', 'MEDIA', 'BRL', 200.0000, ?)
                """,
                purchaseId, worksiteId, costCenterId, categoryId,
                supplierId, actorId, statusId, actorId
        );
        String itemId = item(jdbc, purchaseId, actorId, 0, "2.0000");
        String rollbackItemId = item(
                jdbc, purchaseId, actorId, 1, "1.0000"
        );
        String existingAssetId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO asset (
                    id, source_database, source_table, source_pk,
                    external_code, name, category
                ) VALUES (?, 'TESTE', 'asset', ?, 'EQ-EXISTENTE-1',
                          'Escavadeira existente', 'Máquinas')
                """,
                existingAssetId,
                existingAssetId
        );
        return new Seed(
                actorId,
                purchaseId,
                itemId,
                rollbackItemId,
                existingAssetId
        );
    }

    private String item(
            JdbcTemplate jdbc,
            String purchaseId,
            String actorId,
            int order,
            String quantity
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_compra_item (
                    id, compra_id, ordem, descricao, quantidade, unidade,
                    valor_unitario, valor_total, natureza, criado_por
                ) VALUES (?, ?, ?, 'Equipamento explícito', ?, 'un',
                          100.0000, ?, 'CAPITALIZAVEL', ?)
                """,
                id,
                purchaseId,
                order,
                new BigDecimal(quantity),
                new BigDecimal(quantity).multiply(new BigDecimal("100.0000")),
                actorId
        );
        return id;
    }

    private void authenticate(String actorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                actorId
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }

    private record Seed(
            String actorId,
            String purchaseId,
            String itemId,
            String rollbackItemId,
            String existingAssetId
    ) {
    }
}

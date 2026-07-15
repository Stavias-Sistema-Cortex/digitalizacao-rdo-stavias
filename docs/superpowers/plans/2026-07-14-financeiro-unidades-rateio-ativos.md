# Financeiro por unidades, rateio e ativos — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generalizar o controle financeiro para unidades de obra, ativo e administrativo; preservar as rotas de obra; registrar rateios exatos e auditáveis; e vincular individualmente itens capitalizáveis aos ativos adquiridos.

**Architecture:** `finance_unidade_controle` passa a ser o escopo financeiro explícito. Obras e ativos reais recebem uma unidade própria; `CORPORATIVO` permanece somente escopo de consulta e nunca pode receber rateio. Compras, notas e lançamentos existentes continuam válidos e podem originar um único rateio versionado. Permissões Alfa/Beta são avaliadas por unidade, com adaptadores nas rotas antigas de obra. Toda mutação registra ator, correlação, versão e projeção ontológica.

**Tech Stack:** Java 21, Spring Boot, Spring JDBC, Flyway/MySQL 8, JUnit 5, Mockito, MockMvc, Testcontainers.

## Global Constraints

- Não editar migrações V1–V33; criar somente V34+.
- Não criar obra sintética para representar a empresa.
- Não inferir ativo de texto livre, fornecedor, categoria ou descrição.
- Não conceder permissão Beta implicitamente a partir de vínculo operacional.
- Não aceitar rateio parcial, negativo, em moeda divergente, para destino arquivado ou do tipo `CORPORATIVO`.
- Manter `obraId` nas respostas e rotas legadas enquanto a unidade correspondente é resolvida internamente.
- Exigir `clientMutationId` e `baseVersion` nas mutações atualizáveis.
- Persistir o ator real em toda criação, alteração, revogação e vínculo.
- Projetar as relações `VINCULADO_A_UNIDADE`, `RATEADO_PARA` e `ORIGINOU_ATIVO` no Cortex.

---

### Task 1: Persistir unidades, rateios, histórico e vínculos de ativos

**Files:**

- Create: `apps/api/src/main/resources/db/migration/V34__finance_control_units_allocations_and_assets.sql`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/FinanceControlUnitsMigrationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/FinanceControlUnitsMigrationMysqlIntegrationTest.java`

**Interfaces:**

```sql
finance_unidade_controle(id, tipo, obra_id, ativo_id, codigo, nome, status,
                         origem, criado_por, criado_em, atualizado_por, atualizado_em,
                         arquivado_por, arquivado_em, versao_linha)
finance_rateio(id, client_mutation_id, compra_id, nota_fiscal_id, lancamento_id,
               moeda, valor_total, status, criado_por, criado_em,
               atualizado_por, atualizado_em, versao_linha)
finance_rateio_item(id, rateio_id, unidade_controle_id, centro_custo_id,
                    categoria_id, valor_alocado, percentual, ordem,
                    criado_por, criado_em, atualizado_por, atualizado_em,
                    versao_linha)
finance_rateio_historico(id, rateio_id, operacao, versao_anterior, versao_nova,
                         estado_anterior_json, estado_novo_json, alterado_por,
                         alterado_em, correlacao_id, dispositivo_id)
finance_compra_item_ativo(id, compra_item_id, ativo_id, unidade_controle_id,
                          sequencia, criado_por, criado_em, versao_linha)
finance_compra_item_ativo_historico(id, vinculo_id, operacao,
                                    estado_anterior_json, estado_novo_json,
                                    alterado_por, alterado_em, correlacao_id,
                                    dispositivo_id)
```

- [ ] **Step 1: Write the failing migration contract test**

```java
package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FinanceControlUnitsMigrationTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V34__finance_control_units_allocations_and_assets.sql"
    );

    @Test
    void definesGeneralUnitsExactAllocationsAndIndividualAssets()
            throws Exception {
        String sql = Files.readString(MIGRATION);
        assertThat(sql)
                .contains("CREATE TABLE finance_unidade_controle")
                .contains("'OBRA', 'ATIVO', 'ADMINISTRATIVO', 'CORPORATIVO'")
                .contains("INSERT INTO finance_unidade_controle")
                .contains("SELECT UUID(), 'OBRA'")
                .contains("CREATE TABLE finance_rateio")
                .contains("CREATE TABLE finance_rateio_item")
                .contains("CREATE TABLE finance_rateio_historico")
                .contains("CREATE TABLE finance_compra_item_ativo")
                .contains("CREATE TABLE finance_compra_item_ativo_historico")
                .contains("valor_alocado DECIMAL(19,4)")
                .contains("percentual DECIMAL(9,6)")
                .contains("client_mutation_id")
                .contains("estado_anterior_json")
                .contains("estado_novo_json")
                .contains("correlacao_id")
                .contains("dispositivo_id")
                .contains("natureza VARCHAR(20) NOT NULL DEFAULT 'CONSUMO'");
    }

    @Test
    void keepsCorporateScopeOutOfAutomaticDestinationsAndDoesNotFakeWorksites()
            throws Exception {
        String sql = Files.readString(MIGRATION);
        assertThat(sql)
                .doesNotContain("INSERT INTO obra")
                .doesNotContain("OBRA_CORPORATIVA")
                .contains("chk_fin_unidade_alvo")
                .contains("chk_fin_rateio_origem_unica")
                .contains("UNIQUE (compra_id)")
                .contains("UNIQUE (nota_fiscal_id)")
                .contains("UNIQUE (lancamento_id)")
                .contains("UNIQUE (rateio_id, ordem)")
                .contains("UNIQUE (compra_item_id, sequencia)")
                .contains("UNIQUE (ativo_id)");
    }
}
```

- [ ] **Step 2: Run the contract test and confirm it fails because V34 does not exist**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinanceControlUnitsMigrationTest test`

Expected: FAIL with `NoSuchFileException: ...V34__finance_control_units_allocations_and_assets.sql`.

- [ ] **Step 3: Add the minimal V34 schema and deterministic backfill**

The migration must:

```sql
CREATE TABLE finance_unidade_controle (...);

INSERT INTO finance_unidade_controle (
    id, tipo, obra_id, ativo_id, codigo, nome, status, origem,
    criado_por, atualizado_por
)
SELECT UUID(), 'OBRA', o.id, NULL, CONCAT('OBRA:', o.id), o.nome,
       'ATIVA', 'MIGRACAO_OBRA', NULL, NULL
FROM obra o
WHERE o.arquivado_em IS NULL;

ALTER TABLE permissao_financeira_colaborador
    ADD COLUMN unidade_controle_id CHAR(36) NULL AFTER obra_id;

UPDATE permissao_financeira_colaborador p
JOIN finance_unidade_controle u
  ON u.tipo = 'OBRA' AND u.obra_id = p.obra_id
SET p.unidade_controle_id = u.id;

ALTER TABLE permissao_financeira_colaborador
    MODIFY unidade_controle_id CHAR(36) NOT NULL,
    MODIFY obra_id CHAR(36) NULL,
    DROP INDEX uq_permissao_financeira_colaborador,
    ADD CONSTRAINT uq_permissao_financeira_unidade
        UNIQUE (colaborador_id, unidade_controle_id, permissao),
    ADD CONSTRAINT fk_permissao_financeira_unidade
        FOREIGN KEY (unidade_controle_id)
        REFERENCES finance_unidade_controle(id);

ALTER TABLE finance_compra_item
    ADD COLUMN natureza VARCHAR(20) NOT NULL DEFAULT 'CONSUMO',
    ADD CONSTRAINT chk_fin_compra_item_natureza
        CHECK (natureza IN ('CONSUMO', 'CAPITALIZAVEL'));
```

`finance_unidade_controle` must use a shape check: `OBRA` requires only `obra_id`; `ATIVO` requires only `ativo_id`; `ADMINISTRATIVO` and `CORPORATIVO` require neither. It must unique-index non-null `obra_id` and `ativo_id`. `criado_por`/`atualizado_por` may be null only for rows with `origem = 'MIGRACAO_OBRA'`, because the legacy `obra` table has no author column; every online/offline creation requires a real actor at the service boundary. Rateio source must be exactly one of `compra_id`, `nota_fiscal_id`, `lancamento_id`. All non-null actor columns reference `colaborador(id)`.

- [ ] **Step 4: Add a MySQL integration test for backfill, constraints and referential integrity**

Use the same `@Testcontainers(disabledWithoutDocker = true)`, `MySQLContainer<?>`, Flyway and `JdbcTemplate` setup as `FinanceCoreMigrationMysqlIntegrationTest`. Test all of:

```java
assertThat(count("finance_unidade_controle")).isEqualTo(count("obra"));
assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM permissao_financeira_colaborador "
                + "WHERE unidade_controle_id IS NULL", Long.class
)).isZero();
assertThatThrownBy(() -> jdbc.update(
        "INSERT INTO finance_rateio (...) VALUES (..., ?, ?, ?, ...)",
        compraId, notaFiscalId, null
)).hasRootCauseInstanceOf(java.sql.SQLException.class);
assertThatThrownBy(() -> jdbc.update(
        "INSERT INTO finance_compra_item_ativo (...) VALUES (...)"
)).hasRootCauseInstanceOf(java.sql.SQLException.class);
```

The test must seed a real collaborator, worksite, supplier, configurable statuses, purchase, invoice and asset; no production-like defaults may be added to the migration.

- [ ] **Step 5: Run migration tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinanceControlUnitsMigrationTest,FinanceControlUnitsMigrationMysqlIntegrationTest test`

Expected: PASS; MySQL test is skipped only when Docker is unavailable.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V34__finance_control_units_allocations_and_assets.sql apps/api/src/test/java/com/projeto/cortex/financeiro/FinanceControlUnitsMigrationTest.java apps/api/src/test/java/com/projeto/cortex/pdor/FinanceControlUnitsMigrationMysqlIntegrationTest.java
git commit -m "feat(finance): add control units and allocation schema"
```

---

### Task 2: Expor catálogo de unidades e política de acesso por unidade

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/unit/FinancialUnitType.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/unit/FinancialUnitDtos.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/unit/FinancialUnitRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/unit/FinancialUnitService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/unit/FinancialUnitController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialAccessService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantRepository.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/unit/FinancialUnitServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialAccessServiceTest.java`

**Interfaces:**

```java
public enum FinancialUnitType { OBRA, ATIVO, ADMINISTRATIVO, CORPORATIVO }

public record FinancialUnitResponse(
        String id, FinancialUnitType tipo, String obraId, String ativoId,
        String codigo, String nome, String status, long versao
) {}

public List<FinancialUnitResponse> list(FinancialUnitFilter filter);
public FinancialUnitResponse createAdministrative(CreateFinancialUnitRequest request,
                                                   String actorId);
public FinancialUnitResponse ensureAssetUnit(String assetId, String actorId);
public boolean hasPermissionForUnit(String userId, String unitId,
                                    FinancialPermission permission);
public void requireUnitPermission(String unitId, FinancialPermission permission);
public String resolveWorksiteUnitId(String obraId);
```

- [ ] **Step 1: Write failing unit and access tests**

Cover:

```java
@Test void alfaSeesEveryActiveUnit();
@Test void betaSeesWorksiteUnitOnlyWithWorksiteAccessAndGrant();
@Test void betaSeesAssetUnitOnlyWithExplicitUnitGrant();
@Test void betaNeverSeesAdministrativeOrCorporateUnit();
@Test void administrativeCreationRequiresAlfaAndProjectsActor();
@Test void ensureAssetUnitIsIdempotentAndNeverInfersAnAsset();
```

The access test must assert the exact policy:

```java
when(repository.findUnitScope("unit-asset")).thenReturn(Optional.of(
        new FinancialUnitScope("unit-asset", "ATIVO", null, "asset-1", "ATIVA")
));
when(repository.existsActiveForUnit(
        "beta-1", "unit-asset", FinancialPermission.FINANCEIRO_VISUALIZAR
)).thenReturn(true);
assertThat(service.hasPermissionForUnit(
        "beta-1", "unit-asset", FinancialPermission.FINANCEIRO_VISUALIZAR
)).isTrue();
```

- [ ] **Step 2: Run tests and confirm missing unit classes/methods**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinancialUnitServiceTest,FinancialAccessServiceTest test`

Expected: compilation FAIL for the new unit types and access methods.

- [ ] **Step 3: Implement repository, service and controller**

Expose:

```text
GET  /api/financeiro/unidades?tipo=&status=&busca=
POST /api/financeiro/unidades/administrativas
POST /api/financeiro/unidades/ativos/{ativoId}/garantir
```

`POST` endpoints call `CurrentUserService.requireAlfa()`. `GET` filters in SQL using bound parameters and the access service removes unauthorized units. Creation uses `UUID`, normalized code/name and `FinanceOntologyProjector.success(...)` with actor and `UNIDADE_FINANCEIRA_CRIADA`.

- [ ] **Step 4: Preserve worksite access compatibility**

`hasPermission(userId, obraId, permission)` and `requirePermission(obraId, permission)` remain public and resolve `obraId -> unitId`. The old `allowedObraIds` remains worksite-based. Add unit-specific repository queries without deleting legacy query methods until controllers are migrated.

- [ ] **Step 5: Run focused tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinancialUnitServiceTest,FinancialAccessServiceTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro/unit apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialAccessService.java apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantRepository.java apps/api/src/test/java/com/projeto/cortex/financeiro/unit apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialAccessServiceTest.java
git commit -m "feat(finance): add financial unit catalog and access policy"
```

---

### Task 3: Generalizar concessões e capacidades sem quebrar rotas de obra

**Files:**

- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantRecord.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantResponse.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialCapabilitiesController.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialGrantServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialGrantControllerMockMvcTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialCapabilitiesControllerMockMvcTest.java`

**Interfaces:**

```java
public List<FinancialGrantResponse> listByUnit(String unitId);
public FinancialGrantResponse grantUnit(String unitId, String collaboratorId,
        FinancialPermission permission, String justification, String actorId);
public FinancialGrantResponse revokeUnit(String unitId, String collaboratorId,
        FinancialPermission permission, String justification, String actorId);
```

`FinancialGrantResponse` adds `unidadeControleId`, `unidadeTipo` and keeps nullable `obraId`.

- [ ] **Step 1: Write failing controller and domain tests**

Add exact route expectations:

```java
mockMvc.perform(post("/api/financeiro/unidades/unit-asset/permissoes")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"colaboradorId":"beta-1",
                 "permissao":"FINANCEIRO_VISUALIZAR",
                 "justificativa":"Responsável pelo equipamento adquirido."}
                """))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.unidadeControleId").value("unit-asset"))
    .andExpect(jsonPath("$.obraId").isEmpty());
```

Also assert: only active Beta can receive a grant; Alfa target is rejected; administrative/corporate unit target is rejected for Beta; active grant is idempotent; revocation records actor and ends ontology relation only after the last active permission.

- [ ] **Step 2: Run tests and confirm failures**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinancialGrantServiceTest,FinancialGrantControllerMockMvcTest,FinancialCapabilitiesControllerMockMvcTest test`

Expected: FAIL because unit routes and fields do not exist.

- [ ] **Step 3: Implement generalized grants**

Expose:

```text
GET    /api/financeiro/unidades/{unidadeId}/permissoes
POST   /api/financeiro/unidades/{unidadeId}/permissoes
DELETE /api/financeiro/unidades/{unidadeId}/permissoes/{colaboradorId}/{permissao}
GET    /api/financeiro/capacidades?unidadeId={id}
```

Keep all `/api/obras/{obraId}/permissoes-financeiras` routes as adapters that resolve the worksite unit. `FinancialGrantService.registerEvent` must use schema version 2 and include unit id/type, optional worksite id, collaborator, grant, permission, actor and before/after states. Ontology target is `UNIDADE_FINANCEIRA`, not a fake worksite.

- [ ] **Step 4: Run focused and legacy tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest='com.projeto.cortex.financeiro.access.*Test' test`

Expected: PASS, including legacy worksite routes.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro/access apps/api/src/test/java/com/projeto/cortex/financeiro/access
git commit -m "feat(finance): scope financial grants by control unit"
```

---

### Task 4: Implementar rateio exato, versionado e auditável

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationDtos.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationController.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationControllerMockMvcTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/FinanceAllocationServiceMysqlIntegrationTest.java`

**Interfaces:**

```java
public enum AllocationSourceType { COMPRA, NOTA_FISCAL, LANCAMENTO }

public record SaveAllocationRequest(
        String clientMutationId,
        Long baseVersion,
        AllocationSourceType origemTipo,
        String origemId,
        List<AllocationItemRequest> itens,
        String correlacaoId,
        String dispositivoId
) {}

public record AllocationItemRequest(
        String unidadeControleId,
        String centroCustoId,
        String categoriaId,
        BigDecimal valor
) {}

public AllocationResponse save(SaveAllocationRequest request, String actorId);
public AllocationResponse findBySource(AllocationSourceType type, String sourceId);
public AllocationHistoryResponse history(String allocationId);
```

- [ ] **Step 1: Write failing domain tests**

Required tests:

```java
@Test void derivesPercentagesFromValuesAndKeepsExactTotal();
@Test void rejectsPartialAllocation();
@Test void rejectsNegativeOrZeroItem();
@Test void rejectsCorporateOrArchivedDestination();
@Test void rejectsCurrencyMismatch();
@Test void rejectsStaleBaseVersionWithConflict();
@Test void repeatsClientMutationIdIdempotentlyForSameActor();
@Test void recordsActorBeforeAfterCorrelationAndDevice();
@Test void requiresOperatePermissionOnEveryDestination();
```

Percentage derivation uses `valor.divide(total, 6, RoundingMode.HALF_UP)`; values, not percentages, are authoritative. The final response may display derived percentages but equality is checked only on exact `DECIMAL(19,4)` values.

- [ ] **Step 2: Run tests and confirm missing domain**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinanceAllocationServiceTest,FinanceAllocationControllerMockMvcTest test`

Expected: compilation FAIL for allocation types.

- [ ] **Step 3: Implement the transaction and validation boundary**

Expose:

```text
PUT /api/financeiro/rateios/origens/{tipo}/{origemId}
GET /api/financeiro/rateios/origens/{tipo}/{origemId}
GET /api/financeiro/rateios/{rateioId}/historico
```

Within one transaction: lock the source row and current rateio with `FOR UPDATE`; resolve currency and authoritative net/contracted amount; reject invalid unit types/status; require `FINANCEIRO_OPERAR` for every destination; compare version; replace items; derive percentages; persist before/after history with the real actor; call `FinanceOntologyProjector.success` once for the header and register one `RATEADO_PARA` relation for each destination.

Authoritative source values:

```text
COMPRA       -> COALESCE(valor_contratado, valor_previsto), moeda
NOTA_FISCAL  -> valor_liquido, moeda
LANCAMENTO   -> valor_original, moeda
```

- [ ] **Step 4: Add MySQL integration coverage**

Prove row locking/version conflict, unique source, exact values, rejected corporate target, actor history and rollback when any item fails. Reuse existing finance seed helpers where possible.

- [ ] **Step 5: Run focused tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinanceAllocationServiceTest,FinanceAllocationControllerMockMvcTest,FinanceAllocationServiceMysqlIntegrationTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro/allocation apps/api/src/test/java/com/projeto/cortex/financeiro/allocation apps/api/src/test/java/com/projeto/cortex/pdor/FinanceAllocationServiceMysqlIntegrationTest.java
git commit -m "feat(finance): add exact auditable allocations"
```

---

### Task 5: Vincular itens capitalizáveis a ativos individuais

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetDtos.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/core/FinanceDtos.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/core/FinancePurchaseService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetControllerMockMvcTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/FinancePurchasedAssetServiceMysqlIntegrationTest.java`

**Interfaces:**

```java
public record ConfirmPurchasedAssetsRequest(
        long baseVersion,
        List<PurchasedAssetTarget> ativos,
        String correlacaoId,
        String dispositivoId
) {}

public record PurchasedAssetTarget(
        String ativoExistenteId,
        NewAssetRequest novoAtivo
) {}

public record NewAssetRequest(
        String codigoExterno, String nome, String categoria
) {}

public PurchasedAssetResponse confirm(String purchaseId, String itemId,
        ConfirmPurchasedAssetsRequest request, String actorId);
```

Exactly one of `ativoExistenteId` and `novoAtivo` is required per target.

- [ ] **Step 1: Write failing behavior tests**

Cover: non-capitalizable item rejected; capitalizable quantity must be a positive integer; target count equals item quantity; existing asset must exist and be active; new asset requires explicit data; no text inference; duplicate asset rejected; stale item version rejected; each asset gets an `ATIVO` control unit and link; every actor/change is historized.

- [ ] **Step 2: Run tests and confirm missing domain**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinancePurchasedAssetServiceTest,FinancePurchasedAssetControllerMockMvcTest test`

Expected: compilation FAIL for purchased-asset types.

- [ ] **Step 3: Implement purchase item nature and explicit confirmation**

Add `natureza` to purchase item request/response while defaulting omitted legacy input to `CONSUMO`. Expose:

```text
PUT /api/financeiro/compras/{compraId}/itens/{itemId}/ativos
GET /api/financeiro/compras/{compraId}/itens/{itemId}/ativos
GET /api/financeiro/compras/{compraId}/itens/{itemId}/ativos/historico
```

The service locks purchase item, validates `FINANCEIRO_OPERAR` on its worksite unit, validates explicit targets, inserts new `asset` only when requested, ensures its control unit, persists every individual link and history, and projects `COMPRA_ITEM ORIGINOU_ATIVO ATIVO`. It never maps description/category to an asset automatically.

- [ ] **Step 4: Add integration test and run regression**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinancePurchasedAssetServiceTest,FinancePurchasedAssetControllerMockMvcTest,FinancePurchasedAssetServiceMysqlIntegrationTest,FinancePurchaseServiceMysqlIntegrationTest test`

Expected: PASS and legacy purchase payloads still persist `CONSUMO`.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro/asset apps/api/src/main/java/com/projeto/cortex/financeiro/core/FinanceDtos.java apps/api/src/main/java/com/projeto/cortex/financeiro/core/FinancePurchaseService.java apps/api/src/test/java/com/projeto/cortex/financeiro/asset apps/api/src/test/java/com/projeto/cortex/pdor/FinancePurchasedAssetServiceMysqlIntegrationTest.java
git commit -m "feat(finance): trace capital purchases to individual assets"
```

---

### Task 6: Sincronizar e projetar as novas entidades sem lacunas

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/unit/FinancialUnitSyncOperationHandler.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationSyncOperationHandler.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetSyncOperationHandler.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/core/FinanceOntologyProjector.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/unit/FinancialUnitSyncOperationHandlerTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/allocation/FinanceAllocationSyncOperationHandlerTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/asset/FinancePurchasedAssetSyncOperationHandlerTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncHandlerCoverageTest.java`

**Interfaces:**

```java
entityType = "FINANCE_UNIDADE_CONTROLE"; operations = {"CREATE_ADMINISTRATIVE"}
entityType = "FINANCE_RATEIO"; operations = {"UPSERT"}
entityType = "FINANCE_COMPRA_ITEM_ATIVO"; operations = {"CONFIRM"}
```

- [ ] **Step 1: Write failing sync coverage tests**

Assert registry presence, base-version requirements, actor taken from `SyncMutationContext` rather than payload, idempotency by client mutation id, server snapshot, `SYNCED` status and identical ontology fields between online/offline paths.

- [ ] **Step 2: Run tests and confirm missing handlers**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest=FinancialUnitSyncOperationHandlerTest,FinanceAllocationSyncOperationHandlerTest,FinancePurchasedAssetSyncOperationHandlerTest,SyncHandlerCoverageTest test`

Expected: FAIL because handlers are absent from the registry.

- [ ] **Step 3: Implement thin handlers**

Handlers deserialize payloads with the configured `ObjectMapper`, construct audit context from authenticated sync context, delegate to the same domain services used online, and return canonical server snapshots. Do not duplicate business rules.

Extend `FinanceOntologyProjector` with relation-specific projection so calls can declare:

```java
new FinanceOntologyRelation("UNIDADE_FINANCEIRA", unitId,
                            "VINCULADO_A_UNIDADE");
new FinanceOntologyRelation("UNIDADE_FINANCEIRA", unitId,
                            "RATEADO_PARA");
new FinanceOntologyRelation("ATIVO", assetId, "ORIGINOU_ATIVO");
```

- [ ] **Step 4: Run sync and finance regression**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest='com.projeto.cortex.financeiro.*Test,com.projeto.cortex.sync.SyncHandlerCoverageTest' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro apps/api/src/test/java/com/projeto/cortex/financeiro apps/api/src/test/java/com/projeto/cortex/sync/SyncHandlerCoverageTest.java
git commit -m "feat(finance): sync and project generalized finance entities"
```

---

### Task 7: Verificar compatibilidade, integridade e build completo

**Files:**

- Modify only if a test exposes a real regression in files already touched above.

- [ ] **Step 1: Run migration and access suites**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest='Finance*MigrationTest,com.projeto.cortex.financeiro.access.*Test' test`

Expected: PASS.

- [ ] **Step 2: Run all finance unit tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest='com.projeto.cortex.financeiro.*Test' test`

Expected: PASS.

- [ ] **Step 3: Run MySQL integration tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest='com.projeto.cortex.pdor.Finance*MysqlIntegrationTest' test`

Expected: PASS; tests may be skipped only with explicit Testcontainers evidence that Docker is unavailable.

- [ ] **Step 4: Run the complete API build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Inspect changes and forbidden shortcuts**

Run: `git diff --check && ! rg -n "OBRA_CORPORATIVA|INSERT INTO obra|TODO|TBD" apps/api/src/main/resources/db/migration/V34__finance_control_units_allocations_and_assets.sql apps/api/src/main/java/com/projeto/cortex/financeiro/{unit,allocation,asset}`

Expected: exit 0.

- [ ] **Step 6: Record final verification commit if fixes were necessary**

```bash
git add -A
git commit -m "test(finance): verify generalized finance integrity"
```

# Cortex 3.0 Revenue and PDOR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Financeiro maintain versioned service prices, calculate revenue from accepted RDO production, expose ontology evidence, and make PDOR a reproducible revenue-only projection.

**Architecture:** `item_contratual` remains the stable service catalog while immutable price versions define validity. Each RDO execution snapshots the price-version identity and unit price; revenue ledger and PDOR read the same accepted evidence chain.

**Tech Stack:** Java 21, Spring JDBC, PostgreSQL/Flyway/Testcontainers, BigDecimal, React/TypeScript/IndexedDB, Vitest.

## Global Constraints

- Revenue is accepted quantity × snapshotted unit price.
- Price changes never rewrite historical execution/revenue.
- Overlapping active validity intervals are rejected.
- Rejected/cancelled/rework rows contribute zero unless represented by a separate accepted execution.
- RDO/PDOR have no cost, margin, or cost-quality fields.
- Purchases, invoices, payments, allocations, and accounting cost centers remain factual Financeiro modules.
- Every total is derivable from visible evidence rows.

---

### Task 1: Add immutable service price versions

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V49__service_price_versions.sql`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/catalog/ServicePriceVersion.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/catalog/ServicePriceCatalogRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/catalog/ServicePriceCatalogService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/catalog/ServicePriceCatalogController.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/catalog/ServicePriceCatalogServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/catalog/PostgresqlServicePriceCatalogIT.java`

**Interfaces:**
- Consumes: authorized worksite/contract/service identity, unit, price, validity, source.
- Produces: immutable `ServicePriceVersion` and effective-price lookup by service/date.

- [ ] **Step 1: Write failing overlap/version tests**

```java
@Test
void editingPriceCreatesANewVersionWithoutChangingHistory() {
    ServicePriceVersion v1 = service.create(command("125.00", JAN_1, MAR_31));
    ServicePriceVersion v2 = service.supersede(v1.id(), command("130.00", APR_1, null));
    assertThat(repository.get(v1.id()).unitPrice()).isEqualByComparingTo("125.00");
    assertThat(v2.version()).isEqualTo(v1.version() + 1);
}

@Test
void rejectsOverlappingActiveValidity() {
    service.create(command("125.00", JAN_1, MAR_31));
    assertThatThrownBy(() -> service.create(command("130.00", MAR_1, APR_30)))
            .hasMessageContaining("SERVICE_PRICE_VALIDITY_OVERLAP");
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=ServicePriceCatalogServiceTest test`

Expected: FAIL because catalog types do not exist.

- [ ] **Step 3: Add V49**

```sql
CREATE TABLE service_price_version (
    id varchar(36) PRIMARY KEY,
    item_contratual_id varchar(36) NOT NULL REFERENCES item_contratual(id) ON DELETE RESTRICT,
    version integer NOT NULL CHECK (version > 0),
    unit varchar(30) NOT NULL,
    unit_price numeric(18,4) NOT NULL CHECK (unit_price >= 0),
    valid_from date NOT NULL,
    valid_to date,
    status varchar(30) NOT NULL CHECK (status IN ('ACTIVE','SUPERSEDED','INACTIVE')),
    source varchar(80) NOT NULL,
    created_by varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (item_contratual_id, version),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

ALTER TABLE service_price_version ADD CONSTRAINT service_price_no_overlap
    EXCLUDE USING gist (
      item_contratual_id WITH =,
      daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    ) WHERE (status = 'ACTIVE');
```

Enable `btree_gist` in the migration. Backfill one version per existing `item_contratual` with its current price and validity; record source `CORTEX3_BACKFILL`.

- [ ] **Step 4: Implement service/controller authorization**

Expose scoped list/create/supersede/inactivate under `/api/obras/{obraId}/servicos`. Require both worksite access and Financeiro edit capability for writes. Return stable safe conflict code for overlap.

- [ ] **Step 5: Verify and commit**

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlServicePriceCatalogIT verify`

Expected: PASS.

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V49__service_price_versions.sql apps/api/src/main/java/com/projeto/cortex/financeiro/catalog apps/api/src/test/java/com/projeto/cortex/financeiro/catalog
git commit -m "feat(financeiro): version service prices"
```

### Task 2: Snapshot price evidence on RDO execution

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V50__rdo_execution_revenue_evidence.sql`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoCreateRequest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalDetailService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/revenue/RevenueEvidence.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/revenue/RevenueCalculator.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/revenue/RevenueCalculatorTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/revenue/PostgresqlRevenueEvidenceIT.java`

**Interfaces:**
- Consumes: service execution with `itemContratualId`, `priceVersionId`, quantity, unit, RDO date/status.
- Produces: immutable price snapshot and calculated BRL revenue.

- [ ] **Step 1: Write failing arithmetic/status tests**

```java
@ParameterizedTest
@CsvSource({
    "10.000,125.0000,1250.00",
    "0.333,10.0000,3.33"
})
void calculatesAcceptedRevenue(String quantity, String price, String expected) {
    assertThat(calculator.calculate(ACCEPTED, bd(quantity), bd(price)))
            .isEqualByComparingTo(expected);
}

@ParameterizedTest
@EnumSource(value = ValidationStatus.class, names = {"REJECTED", "CANCELLED"})
void rejectedOrCancelledProductionContributesZero(ValidationStatus status) {
    assertThat(calculator.calculate(status, bd("10"), bd("125"))).isZero();
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=RevenueCalculatorTest test`

Expected: FAIL.

- [ ] **Step 3: Add V50 and backfill evidence**

```sql
ALTER TABLE execucao_servico_rdo
    ADD COLUMN IF NOT EXISTS price_version_id varchar(36) REFERENCES service_price_version(id),
    ADD COLUMN IF NOT EXISTS unit_price_snapshot numeric(18,4),
    ADD COLUMN IF NOT EXISTS revenue_amount numeric(18,2),
    ADD COLUMN IF NOT EXISTS revenue_event_id varchar(36);

ALTER TABLE execucao_servico_rdo
    ADD CONSTRAINT chk_revenue_evidence_complete CHECK (
      (price_version_id IS NULL AND unit_price_snapshot IS NULL AND revenue_amount IS NULL)
      OR
      (price_version_id IS NOT NULL AND unit_price_snapshot IS NOT NULL AND revenue_amount IS NOT NULL)
    );
```

Backfill only rows whose unique effective price is provable. Leave ambiguous historical rows unpriced with explicit coverage; never guess.

- [ ] **Step 4: Validate and snapshot inside the RDO transaction**

Load the exact price version, verify item/worksite/unit/date validity, calculate with `BigDecimal` and `HALF_UP` to scale 2, and persist all evidence before publishing the canonical revenue event.

- [ ] **Step 5: Verify and commit**

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlRevenueEvidenceIT verify`

Expected: PASS, including price supersession preserving old row revenue.

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V50__rdo_execution_revenue_evidence.sql apps/api/src/main/java/com/projeto/cortex/rdos apps/api/src/main/java/com/projeto/cortex/financeiro/revenue apps/api/src/test/java/com/projeto/cortex/financeiro/revenue
git commit -m "feat(financeiro): snapshot RDO revenue evidence"
```

### Task 3: Expose a traceable revenue ledger and ontology evidence

**Files:**
- Create from `feat/financeiro-producao-receita` and adapt to V50: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaController.java`
- Create from `feat/financeiro-producao-receita` and adapt to V50: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaService.java`
- Create from `feat/financeiro-producao-receita` and adapt to V50: `apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/PostgresqlRevenueTraceIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaControllerAuthorizationTest.java`

**Interfaces:**
- Consumes: accepted `execucao_servico_rdo` evidence and graph IDs.
- Produces: scoped rows/components/totals and evidence chain for Financeiro.

- [ ] **Step 1: Write failing trace tests**

```java
RastreioReceitaResponse trace = service.traceExecution(EXECUTION_ID, scopedUser());
assertThat(trace).extracting(
        "rdoId", "executionId", "itemContratualId", "priceVersionId",
        "quantity", "unitPrice", "revenue", "eventId")
        .containsExactly(RDO_ID, EXECUTION_ID, ITEM_ID, PRICE_ID,
                bd("10"), bd("125"), bd("1250"), EVENT_ID);
```

Assert list total equals the exact sum of returned rows and cross-worksite trace is forbidden.

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=RastreioReceitaControllerAuthorizationTest test`

Expected: FAIL or reveal legacy non-price-version trace.

- [ ] **Step 3: Port only the revenue capability**

Use branch `feat/financeiro-producao-receita` as reference for response shape and evidence drawer endpoints, but replace any legacy estimated/cost fields with V50 evidence. Query components first; derive totals in the service response.

- [ ] **Step 4: Project revenue ontology links**

Publish `RDO_EXECUTION -> PRICED_BY -> SERVICE_PRICE_VERSION`, `EXECUTES_SERVICE`, `BELONGS_TO_WORKSITE`, and `PRODUCES_REVENUE` with the canonical event as evidence. Replays must not duplicate edges.

- [ ] **Step 5: Verify and commit**

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlRevenueTraceIT verify`

Run: `mvn -f apps/api/pom.xml -Dtest=RastreioReceitaControllerAuthorizationTest test`

Expected: PASS.

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro apps/api/src/test/java/com/projeto/cortex/financeiro apps/api/src/main/java/com/projeto/cortex/ontology/graph
git commit -m "feat(financeiro): trace revenue ontology evidence"
```

### Task 4: Remove cost and margin semantics from PDOR

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorSnapshot.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorResultadoResponse.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorApplicationService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/RealPdorInputLoader.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/PdorContextBuilder.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/PdorEngine.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/PrevisaoFinanceiraService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/PdorRevenueOnlyContractTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/PostgresqlPdorRevenueEvidenceIT.java`

**Interfaces:**
- Consumes: evidence high-water mark, accepted revenue, remaining contracted quantity, revenue states, explicit algorithm parameters.
- Produces: immutable revenue projection with evidence IDs, coverage, assumptions, algorithm version, and staleness.

- [ ] **Step 1: Write failing contract tests**

```java
assertThat(readSources(PdorSnapshot.class, PdorResultadoResponse.class,
        PdorApplicationService.class, RealPdorInputLoader.class))
        .doesNotContain("custoRealizado", "custoPrevisto", "margem", "margin");

PdorSnapshot snapshot = service.calculate(OBRA_ID, user);
assertThat(snapshot.evidenceIds()).containsExactlyInAnyOrderElementsOf(acceptedEvidenceIds);
assertThat(snapshot.evidenceHighWaterMark()).isEqualTo(currentCommitSequence);
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=PdorRevenueOnlyContractTest test`

Expected: FAIL on current cost/margin fields.

- [ ] **Step 3: Refactor input/output and persistence**

Create a new immutable revenue-only snapshot representation; retain legacy snapshot columns only for read compatibility and never populate them in Cortex 3. Record `algorithmVersion`, `evidenceIds`, `evidenceHighWaterMark`, `coverageCode`, `assumptions`, and UTC `executedAt`.

- [ ] **Step 4: Stop suppressing recalculation failures**

Remove broad catch-and-continue behavior. Return a safe `PDOR_CALCULATION_FAILED` error with correlation ID; keep the previous snapshot labeled stale rather than current.

- [ ] **Step 5: Verify and commit**

Run: `mvn -f apps/api/pom.xml -Dtest='Pdor*Test,PrevisaoFinanceiraPayloadTest' test`

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlPdorRevenueEvidenceIT verify`

Expected: PASS.

```bash
git add apps/api/src/main/java/com/projeto/cortex/pdor apps/api/src/main/java/com/projeto/cortex/intelligence apps/api/src/main/java/com/projeto/cortex/financeiro/PrevisaoFinanceiraService.java apps/api/src/test/java/com/projeto/cortex/pdor
git commit -m "refactor(pdor): project revenue without subjective cost"
```

### Task 5: Build offline Financeiro catalog and revenue trace UI

**Files:**
- Create: `apps/web/src/features/financeiro/ServicePriceCatalogPage.tsx`
- Create: `apps/web/src/features/financeiro/servicePriceApi.ts`
- Create: `apps/web/src/features/financeiro/servicePriceRepository.ts`
- Create from `feat/financeiro-producao-receita` and adapt to V50: `apps/web/src/features/financeiro/FinanceRevenueTracePage.tsx`
- Create from `feat/financeiro-producao-receita` and adapt to V50: `apps/web/src/features/financeiro/FinanceTraceEvidenceDrawer.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.tsx`
- Modify: `apps/web/src/features/financeiro/financeiro.types.ts`
- Modify: `apps/web/src/features/financeiro/financeiroOfflineRepository.ts`
- Create corresponding Vitest files.

**Interfaces:**
- Consumes: service price API/cache, revenue rows, graph evidence, PDOR snapshot.
- Produces: price management, revenue ledger/trace, and cost-free PDOR with offline literal states.

- [ ] **Step 1: Write failing catalog/trace tests**

```ts
it("shows an execution as quantity times snapshotted price", () => {
  render(<FinanceRevenueTracePage rows={[fixture]} />);
  expect(screen.getByText("10,000 × R$ 125,0000")).toBeVisible();
  expect(screen.getByText("R$ 1.250,00")).toBeVisible();
  expect(screen.queryByText(/margem|custo previsto/i)).not.toBeInTheDocument();
});

it("queues a price version offline instead of claiming it is saved", async () => {
  network.offline();
  await savePrice(command);
  expect(await readPrice(command.id)).toMatchObject({ syncStatus: "PENDING" });
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm --prefix apps/web test -- --run src/features/financeiro`

Expected: FAIL on missing catalog/legacy Financeiro UI.

- [ ] **Step 3: Implement price catalog and offline mutations**

Use real obra/service data, validity controls, explicit overlap errors, immutable history, and canonical mutation coordinator. Disable price selection in RDO when coverage is ambiguous/partial.

- [ ] **Step 4: Implement revenue/PDOR trace views**

Totals sum visible evidence rows. The drawer links RDO, execution, service, price version, event, and graph entity IDs. PDOR shows evidence coverage/version/staleness and no cost/margin card.

- [ ] **Step 5: Verify and commit**

Run: `npm --prefix apps/web test -- --run src/features/financeiro src/features/obras/PdorPanel.test.tsx`

Run: `npm --prefix apps/web run build`

Expected: PASS.

```bash
git add apps/web/src/features/financeiro apps/web/src/features/obras
git commit -m "feat(web): manage prices and trace operational revenue"
```

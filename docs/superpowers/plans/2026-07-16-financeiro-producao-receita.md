# Financeiro: Produção, Receita e PDOR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exibir, na Visão geral financeira de cada obra, o resultado operacional dos RDOs por serviço e a projeção PDOR, sem confundir estimativa de receita com faturamento ou caixa.

**Architecture:** Um serviço de consulta no backend agrega `execucao_servico_rdo` por serviço e período e consulta o snapshot financeiro atual já calculado pelo PDOR. Ele expõe um contrato protegido por `FINANCEIRO_VISUALIZAR`. O frontend busca esse contrato somente na Visão geral e o renderiza em um painel independente do resumo de lançamentos.

**Tech Stack:** Spring Boot/JdbcTemplate/JUnit 5; React 19/TypeScript/Vitest; CSS existente do Financeiro.

## Global Constraints

- Não criar dados de receita para serviços sem preço contratual.
- Distinguir receita operacional estimada, medida, aprovada, faturada e recebida em toda a cópia e nos tipos.
- Aplicar `FINANCEIRO_VISUALIZAR` antes da leitura operacional.
- Aplicar `de` e `ate` aos RDOs; não recalcular o snapshot PDOR no navegador.
- Preservar compras, notas, pagamentos, rateios e o resumo de lançamentos já existentes.

---

### Task 1: Contrato e agregação operacional protegida

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroController.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroControllerMockMvcTest.java`

**Interfaces:**
- Consumes: `execucao_servico_rdo`, `item_contratual`, `PrevisaoFinanceiraService.buscarAtual(String)` and `FinancialAccessService`.
- Produces: `GET /api/financeiro/resultado-operacional?obraId={uuid}&de={yyyy-MM-dd?}&ate={yyyy-MM-dd?}`.
- Response root: `obraId`, `de`, `ate`, `producaoRealizada`, `receitaOperacionalEstimada`, `custoRealizado`, `margemAtual`, `margemPercentual`, `receitaMedida`, `receitaAprovada`, `receitaFaturada`, `receitaRecebida`, `servicos`, `pdor`.
- Response service item: `servicoNome`, `unidade`, `quantidadeExecutada`, `custoRealizado`, `receitaOperacionalEstimada`, `margem`, `margemPercentual`, `quantidadeRdos`, `rdoIds`.

- [ ] **Step 1: Write the failing service tests**

Create fixtures with two RDOs for the requested obra and a third RDO for another obra. Assert that the query groups equal service/unit rows, sums quantity/cost/revenue, counts distinct RDO IDs, respects inclusive `de`/`ate`, and returns `null` revenue/margin when the contractual item is absent. Add tests that `ate` before `de` returns the same bad-request domain error convention as `FinanceReportService.ReportFilter.normalized()`.

- [ ] **Step 2: Run the service tests to verify they fail**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ResultadoOperacionalFinanceiroServiceTest test`

Expected: FAIL because `ResultadoOperacionalFinanceiroService` and its response contract do not exist.

- [ ] **Step 3: Implement the minimal response and query service**

Use one aggregate SQL query scoped by `obra_id`, non-archived/canonical RDO service execution records, and optional RDO date bounds. Group by the canonical service identity/name and unit. Use `NULL` rather than zero for `receitaOperacionalEstimada`, `margem`, and `margemPercentual` when price is unavailable. Sum the explicit revenue-state columns separately. Load the latest PDOR/financial forecast through `PrevisaoFinanceiraService.buscarAtual(obraId)` and map its reference date/status/forecast fields without transforming their semantic meaning.

```java
public ResultadoOperacionalFinanceiroResponse buscar(
        String obraId, LocalDate de, LocalDate ate
) {
    // validate UUID and chronological interval before querying
    // return service totals, grouped service rows, and latest forecast snapshot
}
```

- [ ] **Step 4: Add the failing controller authorization and query-binding tests**

In `ResultadoOperacionalFinanceiroControllerMockMvcTest`, assert 403 for a caller without `FINANCEIRO_VISUALIZAR`, 200 with `obraId`, `de`, and `ate`, and 400 for malformed/reversed dates. Assert the controller forwards the parsed dates to the service.

- [ ] **Step 5: Implement the protected endpoint**

Follow `FinanceReportController`: use `@RequestMapping("/api/financeiro")`, require `FinancialPermission.FINANCEIRO_VISUALIZAR`, bind ISO dates with `@DateTimeFormat`, and delegate to `ResultadoOperacionalFinanceiroService.buscar(obraId, de, ate)`.

- [ ] **Step 6: Verify backend green and commit**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ResultadoOperacionalFinanceiroServiceTest,ResultadoOperacionalFinanceiroControllerMockMvcTest test`

Expected: PASS. Commit only the five Task 1 files with message `feat(financeiro): expose operational result by worksite`.

### Task 2: Finance API contract and data loading

**Files:**
- Modify: `apps/web/src/features/financeiro/financeiro.types.ts`
- Modify: `apps/web/src/features/financeiro/financeiroApi.ts`
- Modify: `apps/web/src/features/financeiro/useFinanceiroData.ts`
- Modify: `apps/web/src/features/financeiro/financeiroApi.test.ts`

**Interfaces:**
- Consumes: Task 1 endpoint and existing `FinanceFilters`.
- Produces: `FinanceOperationalResult`, `FinanceOperationalService`, `FinanceOperationalPdor`, `buscarResultadoOperacional(filters)` and `operationalResult` in `FinanceWorkspaceData`.

- [ ] **Step 1: Write the failing API query-contract test**

Add a Vitest test calling `buscarResultadoOperacional(FILTERS)` and assert its URL is `/financeiro/resultado-operacional` with `obraId`, `de`, and `ate`, but without ledger-only filters such as `prioridade`, `tipo`, `moeda`, supplier, cost center, category, status, or free-text query.

- [ ] **Step 2: Run the API test to verify it fails**

Run: `cd apps/web && npm test -- financeiroApi.test.ts`

Expected: FAIL because `buscarResultadoOperacional` is not exported.

- [ ] **Step 3: Implement frontend types and endpoint client**

Add the three exact interfaces in `financeiro.types.ts`. Add `buscarResultadoOperacional(filters)` in `financeiroApi.ts`; construct `URLSearchParams` from only `obraId`, `de`, and `ate`, omitting blank dates, and use the existing `readJson` path.

```ts
export async function buscarResultadoOperacional(
  filters: FinanceFilters,
): Promise<FinanceOperationalResult> {
  const params = new URLSearchParams({ obraId: filters.obraId });
  if (filters.de) params.set("de", filters.de);
  if (filters.ate) params.set("ate", filters.ate);
  return readJson(await apiFetch(endpoint("/financeiro/resultado-operacional", params)));
}
```

- [ ] **Step 4: Write the failing hook behavior test**

Create `apps/web/src/features/financeiro/useFinanceiroData.test.ts` using the project’s React test pattern. Assert that in `visao-geral` it requests overview, ledger, and operational result together; in `compras` it does not request operational result; and a rejected operational request leaves the ordinary overview data available while setting a dedicated operational error state.

- [ ] **Step 5: Implement the minimal hook changes**

Extend `FinanceWorkspaceData` with `operationalResult` and `operationalError`. In the `visao-geral` branch, load `buscarVisaoGeral`, `buscarLancamentos`, and `buscarResultadoOperacional` with independent settlement so a failed operational request does not discard the ledger overview. Reset both fields when access is denied or the selected worksite changes.

- [ ] **Step 6: Verify frontend data layer green and commit**

Run: `cd apps/web && npm test -- financeiroApi.test.ts useFinanceiroData.test.ts`

Expected: PASS. Commit only Task 2 files with message `feat(financeiro): load RDO operational result`.

### Task 3: Resultado operacional na Visão geral

**Files:**
- Create: `apps/web/src/features/financeiro/FinanceOperationalResultPanel.tsx`
- Create: `apps/web/src/features/financeiro/FinanceOperationalResultPanel.test.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceOverviewPanel.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.css`

**Interfaces:**
- Consumes: `FinanceOperationalResult | null`, `operationalError`, current `FinanceFilters`, and `formatMoney`.
- Produces: operational result summary and per-service table rendered only inside Financeiro > Visão geral for an authorized selected worksite.

- [ ] **Step 1: Write the failing component tests**

Test a populated result with Fresagem: assert quantity/unit, cost, revenue operational estimate, margin, RDO count, PDOR label `Projeção PDOR`, and forecast reference date. Test a service whose revenue is `null`: assert `Receita indisponível` and no fabricated `R$ 0`. Test empty result and operational fetch error independently.

- [ ] **Step 2: Run the component tests to verify they fail**

Run: `cd apps/web && npm test -- FinanceOperationalResultPanel.test.tsx`

Expected: FAIL because the panel does not exist.

- [ ] **Step 3: Implement the focused panel**

Render four operational KPIs (production, revenue operational estimated, realized cost, current margin); a separate PDOR block; state labels for measured/approved/invoiced/received revenue; and a semantic table with the service fields. Keep the current restrained Financeiro language and use the existing `finance-table`, empty-state, and money-formatting conventions. Do not embed RDO payloads or local calculations in the component.

```tsx
<section aria-label="Resultado operacional dos RDOs">
  <h2>Produção e resultado operacional</h2>
  <p>Receita operacional estimada pelos serviços executados.</p>
  <table className="finance-table">{/* linhas vindas de result.servicos */}</table>
</section>
```

- [ ] **Step 4: Compose it into the existing overview**

Pass `data.operationalResult` and `data.operationalError` from `FinanceiroPage` to `FinanceOverviewPanel`; render the new panel above the ledger rail. Keep `FinanceOverviewPanel`’s existing ledger/agenda behavior unchanged, including its empty state when there are no financial movements.

- [ ] **Step 5: Verify UI behavior and build, then commit**

Run: `cd apps/web && npm test -- FinanceOperationalResultPanel.test.tsx && npm run build`

Expected: PASS and a clean TypeScript/Vite build. Commit only Task 3 files with message `feat(financeiro): show production revenue and PDOR`.

### Task 4: Full regression and manual evidence

**Files:**
- Modify only if a defect is demonstrated by the commands below.

- [ ] **Step 1: Run focused backend and frontend suites**

Run:

```bash
cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ResultadoOperacionalFinanceiroServiceTest,ResultadoOperacionalFinanceiroControllerMockMvcTest test
cd ../../apps/web && npm test -- financeiroApi.test.ts useFinanceiroData.test.ts FinanceOperationalResultPanel.test.tsx
cd apps/web && npm run lint && npm run build
```

Expected: every command exits 0.

- [ ] **Step 2: Perform a browser smoke with real data**

Start the local API and web app using the project runbook. Log in as a user with `FINANCEIRO_VISUALIZAR`, select Financeiro > Obras > a worksite containing RDO services, and verify: period filters change the operational totals/table; services identify their RDO count; PDOR is called a projection; and finance ledger totals remain visible. Repeat with a worksite with no RDO service and record the truthful empty state.

- [ ] **Step 3: Commit any verified correction only**

If and only if the smoke exposes a defect, add a failing regression test, fix minimally, re-run Step 1, then commit with a specific `fix(financeiro): ...` message. Otherwise create no empty commit.

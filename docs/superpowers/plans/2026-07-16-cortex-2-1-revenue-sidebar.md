# Cortex 2.1 Revenue and Sidebar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the Receita Operacional Rastreável served on localhost:5174 into Cortex 2.1 and replace sidebar strip highlights with full-button frames over a black/green gradient.

**Architecture:** Port the already validated revenue domain commits from `feat/financeiro-producao-receita` instead of recreating their calculations. Resolve the web conflicts in favor of the 5174 revenue-only experience while retaining Cortex 2.1 tokens and the exclusive `Home > Memória` ontology ledger. Implement the sidebar change as CSS-only state styling backed by source policy tests.

**Tech Stack:** React 19, TypeScript, Vite, Vitest, CSS, Spring Boot 3.3, Java 21, MockMvc, MySQL/Flyway.

## Global Constraints

- `localhost:5174` at commit `c569c19` is the authoritative functional reference.
- Financeiro exposes only `Rastreio de receita` in its visible section navigation.
- Revenue values must come from canonical RDO and contract data; unavailable revenue renders `Indisponível`.
- `Home > Memória` remains the only global ontology modification ledger.
- Sidebar background is a gradient between `#111312` and the existing institutional green.
- Yellow/black partial highlights become complete frames around the relevant sidebar button.
- Preserve expanded, collapsed, responsive, keyboard-focus and reduced-motion behavior.
- Do not change StavIA launcher geometry.

---

### Task 1: Lock the revenue-only and sidebar visual contracts

**Files:**
- Modify: `apps/web/src/features/home/institutionalUiPolicy.test.ts`

**Interfaces:**
- Consumes: `FinanceiroPage.tsx` and `index.css` as source artifacts.
- Produces: regression checks for the visible Financeiro scope and sidebar frames.

- [ ] **Step 1: Write the failing policy tests**

Extend the test fixture reads with `FinanceiroPage.tsx`, then assert:

```ts
it("keeps Financeiro focused on traceable operational revenue", () => {
  expect(financePage).toContain('label: "Rastreio de receita"');
  expect(financePage).toContain("FinanceRevenueTracePage");
  expect(financePage).not.toContain('label: "Compras"');
  expect(financePage).not.toContain('label: "Notas fiscais"');
  expect(financePage).not.toContain('label: "Pagamentos e cobranças"');
});

it("frames complete sidebar buttons over a black-green gradient", () => {
  expect(css).toMatch(/\.cortex-sidebar\s*\{[^}]*linear-gradient\([^)]*#111312[^)]*var\(--color-brand-teal\)/s);
  expect(css).toMatch(/\.sidebar-nav-item\.active\s*\{[^}]*border:\s*1px solid var\(--color-brand-yellow\)/s);
  expect(css).not.toContain(".sidebar-nav-item.active::before");
});
```

- [ ] **Step 2: Run the policy test and confirm red state**

Run:

```bash
cd apps/web
npm test -- --run src/features/home/institutionalUiPolicy.test.ts
```

Expected: FAIL because the current Financeiro still lists seven sections and the sidebar still uses a flat background plus `active::before` strip.

- [ ] **Step 3: Commit the red contract**

```bash
git add apps/web/src/features/home/institutionalUiPolicy.test.ts
git commit -m "test(web): define revenue and sidebar contracts"
```

### Task 2: Port the canonical revenue backend

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroControllerAuthorizationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaControllerAuthorizationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaServiceTest.java`

**Interfaces:**
- Produces: `GET /api/financeiro/resultado-operacional` and `GET /api/financeiro/rastreio-receita` with the exact response contracts from `c569c19`.
- Consumes: existing RDO, contractual item, PDOR and authorization repositories.

- [ ] **Step 1: Restore the canonical API and tests**

Use the exact versions from `c569c19`:

```bash
git checkout c569c19 -- \
  apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroController.java \
  apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroResponse.java \
  apps/api/src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroService.java \
  apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaController.java \
  apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaResponse.java \
  apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaService.java \
  apps/api/src/test/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroControllerAuthorizationTest.java \
  apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaControllerAuthorizationTest.java \
  apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaServiceTest.java
```

- [ ] **Step 2: Run the focused Java tests under JDK 21**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q \
  -Dtest=ResultadoOperacionalFinanceiroControllerAuthorizationTest,RastreioReceitaControllerAuthorizationTest,RastreioReceitaServiceTest test
```

Expected: all selected tests PASS.

- [ ] **Step 3: Commit the backend port**

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro \
  apps/api/src/test/java/com/projeto/cortex/financeiro
git commit -m "feat(api): restore traceable operational revenue"
```

### Task 3: Port the 5174 revenue experience into Cortex 2.1

**Files:**
- Create: `apps/web/src/features/financeiro/FinanceRevenueTracePage.tsx`
- Create: `apps/web/src/features/financeiro/FinanceTraceEvidenceDrawer.tsx`
- Create: `apps/web/src/features/financeiro/FinanceOperationalResultPanel.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.css`
- Modify: `apps/web/src/features/financeiro/financeiro.types.ts`
- Modify: `apps/web/src/features/financeiro/financeiroApi.ts`
- Modify: `apps/web/src/features/financeiro/useFinanceiroData.ts`
- Modify: `apps/web/src/features/financeiro/financeiroApi.test.ts`

**Interfaces:**
- Consumes: `buscarRastreioReceita({ obraId, de, ate })` and `FinanceRevenueTrace`.
- Produces: a revenue-only `/financeiro` view with consolidated metrics, revenue states, service rows and evidence drawer.

- [ ] **Step 1: Restore the 5174 components and data contracts**

Copy `FinanceRevenueTracePage.tsx` and `FinanceOperationalResultPanel.tsx` from
`c569c19`. Base `FinanceTraceEvidenceDrawer.tsx` on that commit, but remove the
`buscarAuditoriaFinanceira` request and the inline event list: the drawer keeps
obra, item contratual, RDO IDs, revenue states and PDOR evidence, while global
ontology modifications remain exclusive to `Home > Memória`. Port the revenue
types, `buscarRastreioReceita`, PDOR lookup and operational-result query from the
same commit without reintroducing `FinanceAuditEvent` or
`/ontology/timeline` in Financeiro.

- [ ] **Step 2: Reduce visible Financeiro navigation to one section**

In `FinanceiroPage.tsx`, set:

```ts
const SECTIONS: { id: FinanceSection; label: string }[] = [
  { id: "visao-geral", label: "Rastreio de receita" },
];
```

Render `FinanceRevenueTracePage` for `visao-geral` and remove imports/render branches for Purchases, Invoices, Payments, Allocations, Cost Centers and Reports from this page. Keep their implementation files and backend routes intact.

- [ ] **Step 3: Reconcile the revenue CSS with Cortex 2.1 tokens**

Port `.finance-operational-result`, `.finance-operational-metrics`, `.finance-operational-states`, `.finance-pdor-summary`, `.finance-evidence-backdrop`, and `.finance-evidence-drawer` from `c569c19`. Replace literal legacy geometry/colors with:

```css
border-radius: var(--radius-container);
color: var(--color-ink);
border-color: var(--color-border);
transition: border-color 160ms ease, background-color 160ms ease, color 160ms ease;
```

Keep the current institutional `FinanceiroPage.css` rules needed by the revenue table and drawer.

- [ ] **Step 4: Add an API contract test for the trace endpoint**

In `financeiroApi.test.ts`, assert that `buscarRastreioReceita({ obraId: "obra-1", de: "2026-07-01", ate: "2026-07-31" })` calls:

```text
/financeiro/rastreio-receita?obraId=obra-1&de=2026-07-01&ate=2026-07-31
```

and preserves `receitaDisponivel: false` without substituting totals.

- [ ] **Step 5: Run focused web tests**

```bash
cd apps/web
npm test -- --run src/features/financeiro/financeiroApi.test.ts src/features/home/institutionalUiPolicy.test.ts
```

Expected: revenue API test PASS; policy test still fails only on sidebar styling.

- [ ] **Step 6: Commit the web revenue port**

```bash
git add apps/web/src/features/financeiro apps/web/src/features/home/institutionalUiPolicy.test.ts
git commit -m "feat(web): restore traceable revenue workspace"
```

### Task 4: Apply the sidebar gradient and full-button frames

**Files:**
- Modify: `apps/web/src/index.css`
- Test: `apps/web/src/features/home/institutionalUiPolicy.test.ts`

**Interfaces:**
- Consumes: existing `.cortex-sidebar`, `.sidebar-nav-item`, `.sidebar-footer button`, and `.sidebar-toggle` markup.
- Produces: a responsive gradient sidebar with complete state frames.

- [ ] **Step 1: Replace the flat sidebar background**

Use an intentional diagonal gradient that starts in structural black and resolves into institutional teal:

```css
.cortex-sidebar {
  background: linear-gradient(155deg, #111312 0%, #123a37 45%, var(--color-brand-teal) 100%);
}
```

- [ ] **Step 2: Convert partial highlights into complete frames**

Give navigation and footer buttons a transparent one-pixel frame by default, a restrained light frame on hover, and a yellow full frame when active:

```css
.sidebar-nav-item,
.sidebar-footer button {
  border: 1px solid transparent;
  border-radius: var(--radius-control);
}

.sidebar-nav-item:hover,
.sidebar-footer button:hover {
  border-color: rgb(255 255 255 / 32%);
}

.sidebar-nav-item.active {
  border: 1px solid var(--color-brand-yellow);
  background: rgb(0 0 0 / 20%);
}
```

Delete `.sidebar-nav-item.active::before`. Give `.sidebar-toggle` a complete yellow border rather than a partial highlight and keep `:focus-visible` as a complete outline.

- [ ] **Step 3: Verify compact and responsive states**

Ensure the existing 46px compact buttons keep their full frame and the mobile rules do not override `border-color` or restore the pseudo-element.

- [ ] **Step 4: Run the policy test to green**

```bash
cd apps/web
npm test -- --run src/features/home/institutionalUiPolicy.test.ts
```

Expected: all policy tests PASS.

- [ ] **Step 5: Commit sidebar styling**

```bash
git add apps/web/src/index.css apps/web/src/features/home/institutionalUiPolicy.test.ts
git commit -m "style(web): frame gradient sidebar controls"
```

### Task 5: Full verification and runtime fidelity audit

**Files:**
- Verify only; edit only when a failing requirement identifies a defect.

**Interfaces:**
- Consumes: complete branch state.
- Produces: fresh evidence for every explicit objective requirement.

- [ ] **Step 1: Run the complete web gates**

```bash
cd apps/web
npm test -- --run
npm run lint
npm run build
```

Expected: all commands exit 0.

- [ ] **Step 2: Run focused API tests under JDK 21**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q \
  -Dtest=ResultadoOperacionalFinanceiroControllerAuthorizationTest,RastreioReceitaControllerAuthorizationTest,RastreioReceitaServiceTest,OperationalMemoryQueryServiceTest,OperationalMemoryControllerAuthorizationMockMvcTest test
```

Expected: all selected tests PASS.

- [ ] **Step 3: Audit source fidelity**

```bash
rg -n 'label: "(Compras|Notas fiscais|Pagamentos e cobranças|Rateios|Centros de custo|Relatórios)"' \
  apps/web/src/features/financeiro/FinanceiroPage.tsx
rg -n 'sidebar-nav-item.active::before' apps/web/src/index.css
```

Expected: both searches return no matches. Confirm `Rastreio de receita`, `FinanceRevenueTracePage`, `linear-gradient`, and full active border are present.

- [ ] **Step 4: Run isolated runtime smoke**

Start the branch on web `5177` and API `8083`, then confirm:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8083/api/health
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5177/financeiro
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8083/api/financeiro/rastreio-receita
```

Expected: health `200`, web route `200`, unauthenticated revenue endpoint `401`.

- [ ] **Step 5: Confirm clean delivery state**

```bash
git diff --check
git status --short --branch
```

Expected: no diff errors and a clean `feat/cortex-2-1-memory-ui` worktree.

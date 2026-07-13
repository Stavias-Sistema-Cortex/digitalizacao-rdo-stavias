# Authenticated Platform UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved operational-restraint visual system across the authenticated Stavias Córtex platform without changing product behavior or architecture.

**Architecture:** Keep the work in the existing CSS ownership boundaries. `src/index.css` remains responsible for global tokens, shell, shared controls, Home, RDO, and Obras; feature CSS files remain responsible for Tarefas, sync, integrations, obra administration, and weekly programming. Add one source-level CSS contract test so the visual constraints have an automated regression guard, then verify the rendered app with authenticated Playwright screenshots.

**Tech Stack:** React 19, TypeScript 6, CSS, Vite 8, Vitest 4, Playwright with local Microsoft Edge.

## Global Constraints

- Preserve the existing React architecture, routes, behavior, brand assets, Poppins typography, compact sidebar, and data flows.
- Use `#18231F`, `#124E4A`, `#F2C800`, `#F4F6F4`, `#FFFFFF`, and `#D8DFDA` as the core palette.
- Use 8px, 12px, and 16px as the shared radii.
- Reserve full pills for status badges and compact filter choices.
- Remove glossy gradients and glass effects from the authenticated platform.
- Do not add a UI framework, icon dependency, font dependency, or state-management layer.
- Do not change APIs, authentication, synchronization, routing, offline behavior, domain terminology, PDOR semantics, or permissions.
- Preserve visible keyboard focus, accessible names, semantic status colors, reduced motion, and responsive behavior.
- Keep the login page and established StavIA panel treatment outside the main change set.
- Latest user feedback overrides the shared radius rule for RDO metrics only: metric cards must be square with a complete yellow frame.

---

### Task 1: Add visual-system regression contracts

**Files:**
- Create: `apps/web/src/uiPolish.test.ts`
- Test: `apps/web/src/uiPolish.test.ts`

**Interfaces:**
- Consumes: CSS source files as plain text.
- Produces: automated contracts for tokens, flat shared controls, the shell, RDO metrics, and keyboard focus.

- [ ] **Step 1: Write the failing CSS contract test**

```ts
import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function readCss(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

function rule(css: string, selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) {
    throw new Error(`Regra CSS ausente: ${selector}`);
  }
  const end = css.indexOf("\n}", start);
  if (end < 0) {
    throw new Error(`Regra CSS sem fechamento: ${selector}`);
  }
  return css.slice(start, end + 2);
}

const globalCss = readCss("./index.css");
const authenticatedCss = [
  globalCss,
  readCss("./components/SyncStatusBanner.css"),
  readCss("./features/integracoes/IntegracoesPage.css"),
  readCss("./features/obras/gestao/gestaoObras.css"),
  readCss("./features/programacoes/ProgramacaoSemanalImport.css"),
  readCss("./features/tarefas/TarefasPage.css"),
].join("\n");

describe("polimento visual da plataforma autenticada", () => {
  it("centraliza a paleta e a escala de raios aprovadas", () => {
    expect(rule(globalCss, ":root")).toContain("--color-text: #18231f;");
    expect(rule(globalCss, ":root")).toContain("--color-brand-teal: #124e4a;");
    expect(rule(globalCss, ":root")).toContain("--color-brand-yellow: #f2c800;");
    expect(rule(globalCss, ":root")).toContain("--radius-sm: 8px;");
    expect(rule(globalCss, ":root")).toContain("--radius-md: 12px;");
    expect(rule(globalCss, ":root")).toContain("--radius-lg: 16px;");
  });

  it("remove receitas de vidro da interface autenticada", () => {
    expect(authenticatedCss).not.toContain("--glass-");
    expect(authenticatedCss).not.toContain("backdrop-filter");
  });

  it("usa uma sidebar plana e métricas operacionais discretas", () => {
    expect(rule(globalCss, ".cortex-sidebar")).toContain(
      "background: var(--color-brand-teal);",
    );
    expect(rule(globalCss, ".cortex-sidebar")).not.toContain("radial-gradient");
    expect(rule(globalCss, ".metric-card")).toContain(
      "border-top: 3px solid var(--color-brand-yellow);",
    );
    expect(rule(globalCss, ".metric-card")).toContain(
      "background: var(--color-surface);",
    );
  });

  it("mantém foco visível para os controles principais", () => {
    expect(globalCss).toContain("button:focus-visible");
    expect(globalCss).toContain("outline: 3px solid var(--color-focus);");
  });
});
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd apps/web && npx vitest run src/uiPolish.test.ts`

Expected: FAIL because the approved color tokens and flat-surface contracts are not present yet.

- [ ] **Step 3: Commit the failing contract**

```bash
git add apps/web/src/uiPolish.test.ts
git commit -m "test: define authenticated UI polish contracts"
```

---

### Task 2: Apply the shared tokens, shell, and control language

**Files:**
- Modify: `apps/web/src/index.css:43-917`
- Modify: `apps/web/src/components/SyncStatusBanner.css:1-226`
- Modify: `apps/web/src/features/tarefas/TarefasPage.css:1-416`
- Test: `apps/web/src/uiPolish.test.ts`

**Interfaces:**
- Consumes: approved palette and radius values from the design spec.
- Produces: `--color-*`, `--radius-*`, and `--shadow-overlay` tokens used by every later task.

- [ ] **Step 1: Replace the global token block**

```css
:root {
  font-family: "Poppins", ui-sans-serif, system-ui, -apple-system,
    BlinkMacSystemFont, "Segoe UI", sans-serif;
  color: var(--color-text);
  background: var(--color-canvas);
  font-synthesis: none;
  text-rendering: optimizeLegibility;

  --color-text: #18231f;
  --color-brand-teal: #124e4a;
  --color-brand-teal-strong: #0d3f3c;
  --color-brand-yellow: #f2c800;
  --color-canvas: #f4f6f4;
  --color-surface: #ffffff;
  --color-border: #d8dfda;
  --color-muted: #68756e;
  --color-focus: #2f6f68;
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --shadow-overlay: 0 18px 44px rgb(24 35 31 / 16%);
}
```

- [ ] **Step 2: Flatten shared buttons, form surfaces, and the action bar**

Use the following declarations for both shared button families and the sticky action container, then remove all `--glass-*`, gradient, blur, and static-card shadow declarations from the authenticated rules in `index.css`:

```css
.primary-button,
.button.primary {
  border: 1px solid #d6b000;
  border-radius: var(--radius-sm);
  background: var(--color-brand-yellow);
  color: #123b37;
  box-shadow: none;
}

.secondary-button,
.button.secondary {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-brand-teal);
  box-shadow: none;
}

.action-bar {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: 0 8px 24px rgb(24 35 31 / 10%);
}
```

- [ ] **Step 3: Simplify the sidebar while preserving behavior**

```css
.cortex-sidebar {
  background: var(--color-brand-teal);
  color: #eef7ef;
}

.sidebar-nav-item img,
.sidebar-footer button img {
  filter: grayscale(1) brightness(0) invert(1);
  opacity: 0.72;
}

.sidebar-nav-item.active {
  background: rgb(255 255 255 / 10%);
}
```

Keep the existing grid widths, resizer behavior, collapsed selectors, localStorage behavior, brand lockup, and yellow active marker unchanged.

- [ ] **Step 4: Flatten sync and profile overlays**

In `SyncStatusBanner.css`, use the following white surfaces and reserve `var(--shadow-overlay)` for the popover. Remove backdrop filters and reveal animation while retaining sync spin/pulse and reduced-motion rules.

```css
.sync-chip__button {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  box-shadow: none;
}

.sync-chip__popover {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: var(--shadow-overlay);
  animation: none;
}
```

- [ ] **Step 5: Remove the remaining Tarefas glass primitives**

Remove the `--glass-*`, `backdrop-filter`, and translucent glass declarations from `TarefasPage.css` in the same task so the authenticated CSS contract can become green. Keep Tarefas layout, priority colors, and interaction states unchanged; Task 4 performs the remaining hierarchy alignment.

```css
.tarefas-card {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  box-shadow: none;
}

.tarefas-equipe-tab--active,
.tarefas-nova-equipe button {
  background: var(--color-brand-yellow);
  box-shadow: none;
}
```

- [ ] **Step 6: Add a shared focus-visible rule**

```css
button:focus-visible,
a:focus-visible,
input:focus-visible,
select:focus-visible,
textarea:focus-visible,
[role="separator"]:focus-visible {
  outline: 3px solid var(--color-focus);
  outline-offset: 2px;
}
```

- [ ] **Step 7: Run the focused contract and full tests**

Run: `cd apps/web && npx vitest run src/uiPolish.test.ts`

Expected: PASS.

Run: `cd apps/web && npm run test`

Expected: all tests pass.

- [ ] **Step 8: Commit the shared system**

```bash
git add apps/web/src/index.css apps/web/src/components/SyncStatusBanner.css \
  apps/web/src/features/tarefas/TarefasPage.css
git commit -m "style: refine authenticated app shell"
```

---

### Task 3: Refine Home, RDO, and Obras hierarchy

**Files:**
- Modify: `apps/web/src/index.css:919-2412`
- Test: `apps/web/src/uiPolish.test.ts`

**Interfaces:**
- Consumes: the global color, radius, focus, and surface tokens from Task 2.
- Produces: consistent authenticated page headers, filters, cards, metric blocks, lists, timelines, and empty states.

- [ ] **Step 1: Rebalance Home**

Apply the following hierarchy, retain wrapping on `.home-chips`, and retain the dark teal `Mais Stavias` card as the single deliberate brand block:

```css
.home-dashboard {
  gap: 18px;
  padding: 56px clamp(20px, 3vw, 44px) 120px;
}

.home-topbar {
  align-items: flex-end;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border);
}

.home-topbar h1 {
  margin-right: 8px;
  font-size: 1.35rem;
  font-weight: 700;
}

.chip,
.home-uf-filter select {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

.chip--active {
  border-color: #d6b000;
  background: var(--color-brand-yellow);
}

.home-obra-card,
.home-card {
  border: 1px solid var(--color-border);
  box-shadow: none;
}

@media (max-width: 1180px) {
  .home-uf-filter {
    width: 100%;
    margin-left: 0;
    flex-wrap: wrap;
  }
}
```

- [ ] **Step 2: Refine the RDO command and metric hierarchy**

```css
.rdo-command-band,
.rdo-filter-grid,
.rdo-operational-card,
.timeline-panel,
.stavia-suggestion-panel {
  border-color: var(--color-border);
  background: var(--color-surface);
}

.metric-card {
  min-height: 82px;
  padding: 14px 16px;
  border: 1px solid var(--color-border);
  border-top: 3px solid var(--color-brand-yellow);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}
```

Left-align metric labels and numbers, remove uppercase tracking from RDO filter labels, use quiet fact blocks, and keep all status badge rules semantic:

```css
.rdo-filter-grid label,
.metric-card span {
  letter-spacing: 0;
  text-transform: none;
}

.metric-card span,
.metric-card strong {
  text-align: left;
}

.rdo-fact {
  border: 1px solid #e7ebe8;
  border-radius: var(--radius-sm);
  background: #f7f9f7;
}
```

- [ ] **Step 3: Refine Obras and PDOR surfaces**

Use the following selected-state and surface treatment, and keep PDOR revenue/risk colors unchanged:

```css
.obras-list,
.obras-detail {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: none;
}

.obras-list-item {
  position: relative;
  border-radius: var(--radius-sm);
}

.obras-list-item.active {
  border-color: var(--color-border);
  background: #f7f9f7;
}

.obras-list-item.active::before {
  content: "";
  position: absolute;
  inset: 8px auto 8px 0;
  width: 3px;
  border-radius: 999px;
  background: var(--color-brand-yellow);
}
```

- [ ] **Step 4: Repair responsive CSS structure and overflow**

Remove the duplicated malformed `.rdo-card-facts` selector in the `max-width: 900px` block and use these narrow-screen overrides so the Home filters, RDO action area, metric grids, Obras workspace, and sticky action bar collapse without horizontal overflow at 390px:

```css
@media (max-width: 620px) {
  .home-dashboard {
    padding: 24px 14px 96px;
  }

  .home-uf-filter,
  .home-uf-filter select,
  .home-obra-selector,
  .home-obra-selector select {
    width: 100%;
    max-width: none;
  }

  .home-obra-chart {
    min-width: 0;
    width: 100%;
  }

  .rdo-command-actions,
  .rdo-command-actions button,
  .action-bar .button {
    width: 100%;
  }
}
```

- [ ] **Step 5: Run tests and build**

Run: `cd apps/web && npm run test`

Expected: all tests pass.

Run: `cd apps/web && npm run build`

Expected: TypeScript and Vite production build complete with exit code 0.

- [ ] **Step 6: Commit the core pages**

```bash
git add apps/web/src/index.css
git commit -m "style: clarify operational page hierarchy"
```

---

### Task 4: Align authenticated feature surfaces

**Files:**
- Modify: `apps/web/src/features/tarefas/TarefasPage.css:1-416`
- Modify: `apps/web/src/features/integracoes/IntegracoesPage.css:1-62`
- Modify: `apps/web/src/features/obras/gestao/gestaoObras.css:1-228`
- Modify: `apps/web/src/features/programacoes/ProgramacaoSemanalImport.css:1-224`
- Test: `apps/web/src/uiPolish.test.ts`

**Interfaces:**
- Consumes: Task 2 global tokens.
- Produces: consistent Tarefas, integrations, obra administration, and weekly-programming surfaces.

- [ ] **Step 1: Flatten Tarefas cards and controls**

Continue the flat Tarefas treatment established in Task 2 by normalizing item, form, priority, chart, and empty-state surfaces. Preserve priority colors, completion states, team tabs, charts, forms, and responsive layout.

```css
.tarefas-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: none;
}

.tarefas-equipe-tab--active,
.tarefas-nova-equipe button,
.tarefa-form button[type="submit"] {
  background: var(--color-brand-yellow);
  box-shadow: none;
}

.tarefa-item,
.tarefa-form input,
.tarefa-form textarea,
.tarefa-prio-botao {
  border-color: var(--color-border);
  background: var(--color-surface);
}
```

- [ ] **Step 2: Normalize integrations and obra administration**

Use the following shared rules and remove the automatic dark color-scheme override from `gestaoObras.css` because it creates a separate unapproved visual system inside the authenticated shell:

```css
.integracoes-table th,
.integracoes-report dt {
  color: var(--color-muted);
  letter-spacing: 0;
}

.gestao-obras-coluna {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.gestao-obras-item.ativo {
  background: #f7f9f7;
  box-shadow: inset 3px 0 0 var(--color-brand-yellow);
  outline: none;
}
```

- [ ] **Step 3: Normalize weekly programming**

Apply the following shared rules while preserving warning, downloaded, selected-day, and match semantic states:

```css
.programacao-import__summary div,
.programacao-day,
.programacao-lines-table input {
  border-color: var(--color-border);
  border-radius: var(--radius-sm);
}

.programacao-import__summary div {
  background: #f7f9f7;
}

.programacao-day--selected {
  border-color: var(--color-brand-teal);
  outline: 3px solid rgb(18 78 74 / 16%);
}
```

- [ ] **Step 4: Run CSS contracts, tests, lint, and build**

Run: `cd apps/web && npx vitest run src/uiPolish.test.ts`

Expected: PASS.

Run: `cd apps/web && npm run test`

Expected: all tests pass.

Run: `cd apps/web && npm run lint`

Expected: exit code 0, or a documented pre-existing failure with no errors in changed files.

Run: `cd apps/web && npm run build`

Expected: exit code 0.

- [ ] **Step 5: Commit the feature surfaces**

```bash
git add apps/web/src/features/tarefas/TarefasPage.css \
  apps/web/src/features/integracoes/IntegracoesPage.css \
  apps/web/src/features/obras/gestao/gestaoObras.css \
  apps/web/src/features/programacoes/ProgramacaoSemanalImport.css
git commit -m "style: align authenticated feature surfaces"
```

---

### Task 5: Browser verification and final restraint pass

**Files:**
- Modify: `apps/web/src/index.css`
- Modify: `apps/web/src/uiPolish.test.ts`
- Modify if required by evidence: other authenticated CSS files from Tasks 2–4
- Verify: rendered local app at `http://127.0.0.1:5173`

**Interfaces:**
- Consumes: the complete authenticated UI polish.
- Produces: fresh visual evidence and any evidence-driven CSS corrections.

- [ ] **Step 1: Write the failing square-frame metric contract**

Replace the earlier top-accent expectation in `uiPolish.test.ts` with the user's latest visual requirement:

```ts
it("enquadra métricas RDO com moldura amarela e cantos quadrados", () => {
  const metricCard = rule(globalCss, ".metric-card");
  expect(metricCard).toContain(
    "border: 2px solid var(--color-brand-yellow);",
  );
  expect(metricCard).toContain("border-radius: 0;");
  expect(metricCard).not.toContain("border-top:");
});
```

Run: `cd apps/web && npx vitest run src/uiPolish.test.ts`

Expected: FAIL because the current card still has a rounded top-only accent.

- [ ] **Step 2: Implement the requested metric frame and verify GREEN**

```css
.metric-card {
  border: 2px solid var(--color-brand-yellow);
  border-radius: 0;
}
```

Run: `cd apps/web && npx vitest run src/uiPolish.test.ts`

Expected: PASS.

- [ ] **Step 3: Capture desktop screenshots**

Use Playwright with `/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge`, a 1440×1000 viewport, and a local `cortex.auth.sessao` preview session. Capture Home and RDO after waiting for network idle.

- [ ] **Step 4: Exercise shell interactions**

Verify expanded and collapsed sidebar states, profile menu, sync popover, top-level navigation, Home filters, RDO filters, and the StavIA launcher. Confirm accessible names and visible focus on icon-only controls.

- [ ] **Step 5: Capture mobile screenshots**

Use a 390×844 viewport. Capture Home, RDO, Obras, and Tarefas and assert `document.documentElement.scrollWidth <= document.documentElement.clientWidth` on each route.

- [ ] **Step 6: Apply the final restraint pass**

Remove any remaining non-functional glow, oversized radius, decorative gradient, or inconsistent static shadow revealed by the screenshots. Do not change the login composition or StavIA panel structure.

- [ ] **Step 7: Run final verification**

Run: `cd apps/web && npm run test && npm run lint && npm run build`

Expected: all three commands complete successfully.

- [ ] **Step 8: Commit evidence-driven corrections**

```bash
git add apps/web/src
git commit -m "style: finish authenticated UI polish"
```

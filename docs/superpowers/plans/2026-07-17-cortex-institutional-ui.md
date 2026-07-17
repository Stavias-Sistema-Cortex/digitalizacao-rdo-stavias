# Córtex Institutional Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace playful visual patterns with one sober, data-dense institutional interface across login, shell and every tab.

**Architecture:** Define shared page, status and trace primitives first, then migrate modules without changing their domain behavior. CSS remains colocated with existing pages where already established; global tokens and shell primitives stay in `index.css`. Visual regression contracts and desktop/mobile screenshots guard the result.

**Tech Stack:** React 19, TypeScript 6, CSS, Poppins, Vitest, Vite, Microsoft Edge CDP.

## Global Constraints

- Requires the approved design spec and functional offline state interfaces.
- Preserve Poppins; body uses 400/500 and headings use at most 600.
- Black and green carry structure; yellow is limited to selection, attention, primary action and semantic marker.
- Containers use complete 1px/2px frames and 2px–4px radii.
- No isolated thick top stripes, glassmorphism, glow, bounce or decorative pills.
- Financeiro remains revenue-only.
- Full ontology history remains exclusive to `Home > Memória`.
- Keep visible keyboard focus and `prefers-reduced-motion`.
- Do not add `ink-reveal`.

---

### Task 1: Shared institutional primitives and tokens

**Files:**
- Create: `apps/web/src/components/institutional/InstitutionalPageHeader.tsx`
- Create: `apps/web/src/components/institutional/SyncStateStrip.tsx`
- Create: `apps/web/src/components/institutional/TraceReference.tsx`
- Create: `apps/web/src/components/institutional/InstitutionalStatus.tsx`
- Create: `apps/web/src/components/institutional/institutional.css`
- Create: `apps/web/src/components/institutional/institutional.test.tsx`
- Modify: `apps/web/src/index.css`

**Interfaces:**
- Produces: consistent page header, sync state, short trace link and rectangular statuses.
- Consumes: `SyncStatusSnapshot`, route href and event/entity IDs.

- [ ] **Step 1: Write structural and accessibility tests**

```tsx
const markup = renderToStaticMarkup(
  <InstitutionalPageHeader eyebrow="Ontologia operacional" title="Obras" />,
);
expect(markup).toContain("<h1>Obras</h1>");
expect(markup).toContain("Ontologia operacional");
```

Assert that `TraceReference` links to `/home?tab=memory&event=<id>` and does not render a timeline.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/components/institutional/institutional.test.tsx`

Expected: FAIL because components are missing.

- [ ] **Step 3: Implement primitives**

Use semantic `header`, `dl`, `time`, `a` and `role="status"`. `InstitutionalStatus` accepts only `LOCAL`, `PENDING`, `SYNCING`, `SYNCED`, `CONFLICT`, `REJECTED`.

- [ ] **Step 4: Consolidate global tokens**

Keep:

```css
--color-ink: #111312;
--color-brand-teal: #124e4a;
--color-brand-yellow: #f2c800;
--radius-control: 4px;
--radius-container: 4px;
--transition-functional: 160ms ease;
```

Add tabular numeric utility and shared frame/page layout classes. Remove generic rules that recreate thick top-only accents.

- [ ] **Step 5: Run component and policy tests**

Run: `cd apps/web && npm test -- --run src/components/institutional/institutional.test.tsx src/features/home/institutionalUiPolicy.test.ts src/uiPolish.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/components/institutional apps/web/src/index.css apps/web/src/features/home/institutionalUiPolicy.test.ts apps/web/src/uiPolish.test.ts
git commit -m "feat(ui): add institutional interface primitives"
```

### Task 2: Shell and global synchronization state

**Files:**
- Modify: `apps/web/src/components/shell/CortexShell.tsx`
- Modify: `apps/web/src/components/SyncStatusBanner.tsx`
- Modify: `apps/web/src/components/SyncStatusBanner.css`
- Modify: `apps/web/src/index.css`
- Modify: `apps/web/src/uiPolish.test.ts`

**Interfaces:**
- Consumes: `useSyncStatus()` and shared `SyncStateStrip`.
- Produces: sidebar black-green gradient with only a vertical yellow active bar.

- [ ] **Step 1: Extend shell policy tests**

Assert no yellow frame on `.sidebar-nav-item.active`, exactly one vertical marker, no nav pill radius, and global state text for pending/conflict/last-sync.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/uiPolish.test.ts src/features/home/institutionalUiPolicy.test.ts`

Expected: FAIL for missing global state detail.

- [ ] **Step 3: Implement the institutional shell**

Keep current collapse/resize behavior. Replace decorative icons only where semantics are unclear, maintain white icon treatment, and place `SyncStateStrip` beside the profile controls without covering content.

- [ ] **Step 4: Run policy tests**

Run: `cd apps/web && npm test -- --run src/uiPolish.test.ts src/features/home/institutionalUiPolicy.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/components/shell/CortexShell.tsx apps/web/src/components/SyncStatusBanner.tsx apps/web/src/components/SyncStatusBanner.css apps/web/src/index.css apps/web/src/uiPolish.test.ts
git commit -m "style(shell): expose sober global sync state"
```

### Task 3: Official login lockup and security surfaces

**Files:**
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.css`
- Modify: `apps/web/src/features/auth/LoginPage.authPolicy.test.ts`
- Modify: `apps/web/src/features/auth/OfflineUnlockPage.css`
- Modify: `apps/web/src/features/auth/DeviceSecurityPage.css`

**Interfaces:**
- Uses: `apps/web/src/assets/login/cortex-logo.png`.
- Produces: accessible `alt="Stavias Córtex"`, max-width 440px desktop and 100% mobile.

- [ ] **Step 1: Write the failing brand regression test**

```ts
expect(loginPageSource).toContain('import cortexLogo from "../../assets/login/cortex-logo.png"');
expect(loginPageSource).toContain('alt="Stavias Córtex"');
expect(loginPageSource).not.toContain("staviasTile");
expect(loginCss).toMatch(/\.login__brand-lockup\s*\{[^}]*max-width:\s*440px/s);
```

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/features/auth/LoginPage.authPolicy.test.ts`

Expected: FAIL because the login still composes tile + text.

- [ ] **Step 3: Replace the composed brand**

Render one non-draggable image in the existing brand area. Preserve the government-style two-panel layout and black-green gradient. Do not add photo, blur or `ink-reveal`.

- [ ] **Step 4: Align offline/security cards**

Use complete frames, direct security labels, no oversized shadows and the same status vocabulary.

- [ ] **Step 5: Run auth visual tests**

Run: `cd apps/web && npm test -- --run src/features/auth/LoginPage.authPolicy.test.ts src/features/auth/DeviceSecurityPage.policy.test.ts src/features/home/institutionalUiPolicy.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/features/auth
git commit -m "style(auth): use official Cortex institutional identity"
```

### Task 4: Home command view and Memory ledger

**Files:**
- Modify: `apps/web/src/features/home/HomePage.tsx`
- Modify: `apps/web/src/features/home/HomeOverview.tsx`
- Modify: `apps/web/src/features/home/ObraFocusCard.tsx`
- Modify: `apps/web/src/features/home/MemorySummaryCard.tsx`
- Modify: `apps/web/src/features/home/memory/MemoryLedger.tsx`
- Modify: `apps/web/src/features/home/memory/MemoryLedger.css`
- Create: `apps/web/src/features/home/homeInstitutionalLayout.test.ts`

**Interfaces:**
- Consumes: local freshness, sync summary and conflict sections.
- Produces: command view with exceptions first and technical Memory table.

- [ ] **Step 1: Write layout contract tests**

Assert standard header, freshness text, exception register, no generic brand/link card in primary metrics, Memory filters and review section.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/features/home/homeInstitutionalLayout.test.ts`

Expected: FAIL before the command layout exists.

- [ ] **Step 3: Recompose Home**

Place operational exceptions, focused worksite and revenue/sync summaries before secondary links. Use tables/definition lists where data is repeated.

- [ ] **Step 4: Recompose Memory**

Render event ID, time, actor, device, entity, action, origin and result in a dense ledger. Full history remains only here.

- [ ] **Step 5: Run Home suites**

Run: `cd apps/web && npm test -- --run src/features/home`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/features/home
git commit -m "style(home): establish command view and technical memory"
```

### Task 5: RDO document workspace

**Files:**
- Modify: `apps/web/src/features/rdos/RdoWorkspacePage.tsx`
- Modify: `apps/web/src/features/rdos/RdoLocalList.tsx`
- Modify: `apps/web/src/features/rdos/RdoCreatePage.tsx`
- Create: `apps/web/src/features/rdos/RdoWorkspacePage.css`
- Create: `apps/web/src/features/rdos/rdoInstitutionalLayout.test.ts`

**Interfaces:**
- Consumes: local completeness and canonical sync status.
- Produces: document index, completeness matrix and compact trace reference.

- [ ] **Step 1: Write RDO layout tests**

Assert one h1, document index, per-section completion, autosave state, rectangular status and no decorative metric pills.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/features/rdos/rdoInstitutionalLayout.test.ts`

Expected: FAIL.

- [ ] **Step 3: Implement document workspace**

Use a narrow index rail and main document surface. Keep all form semantics and attachment controls. Show local save and server confirmation separately.

- [ ] **Step 4: Run RDO and UI tests**

Run: `cd apps/web && npm test -- --run src/features/rdos src/uiPolish.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/features/rdos
git commit -m "style(rdo): present records as operational documents"
```

### Task 6: Obras and administrative worksite views

**Files:**
- Modify: `apps/web/src/features/obras/ObrasPage.tsx`
- Modify: `apps/web/src/features/obras/gestao/GestaoObrasPage.tsx`
- Modify: `apps/web/src/features/obras/gestao/gestaoObras.css`
- Modify: `apps/web/src/features/obras/map/OperationalMap.css`
- Modify: `apps/web/src/index.css`
- Create: `apps/web/src/features/obras/obrasInstitutionalLayout.test.ts`

**Interfaces:**
- Produces: master-detail worksite register and explicit cached-map state.

- [ ] **Step 1: Write Obras layout tests**

Assert complete detail frame, black `ATIVA` text with yellow marker, warm fact gradient, no local StavIA button, trace link and table-based admin view.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/features/obras/obrasInstitutionalLayout.test.ts src/features/home/institutionalUiPolicy.test.ts`

Expected: FAIL for missing standardized header/freshness/trace states.

- [ ] **Step 3: Implement worksite register**

Preserve approved frames and fact gradient. Reduce ornamental chips; filters become one compact query bar. Map states say “Mapa armazenado”, “Conectando” or “Área não armazenada”.

- [ ] **Step 4: Implement administrative tables**

Show role/vinculation effective dates, pending state, actor and exact action. Destructive changes require typed justification, not playful modal copy.

- [ ] **Step 5: Run Obras suites**

Run: `cd apps/web && npm test -- --run src/features/obras src/features/home/institutionalUiPolicy.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/features/obras apps/web/src/index.css
git commit -m "style(obras): formalize worksite and governance views"
```

### Task 7: Equipes, Mensagens and Tarefas operational registers

**Files:**
- Modify: `apps/web/src/features/equipes/EquipesPage.tsx`
- Modify: `apps/web/src/features/equipes/EquipesPage.css`
- Modify: `apps/web/src/features/mensagens/MensagensPage.tsx`
- Modify: `apps/web/src/features/mensagens/MensagensPage.css`
- Modify: `apps/web/src/features/tarefas/TarefasPage.tsx`
- Modify: `apps/web/src/features/tarefas/TarefasPage.css`
- Create: `apps/web/src/features/operationalTabsInstitutional.test.ts`

**Interfaces:**
- Produces: team registry, sober message workspace and table-first task register.

- [ ] **Step 1: Write cross-tab visual contracts**

Assert standard page headers, visible pending states, no decorative avatars, Tarefas table as default, and trace references without embedded history.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/features/operationalTabsInstitutional.test.ts`

Expected: FAIL.

- [ ] **Step 3: Formalize Equipes**

Use team/member tables with role, worksite, validity, version and pending status. Keep detail in a right-side panel.

- [ ] **Step 4: Formalize Mensagens**

Keep the three-pane interaction, remove decorative avatar emphasis and make delivery/attachment state typographic and compact.

- [ ] **Step 5: Formalize Tarefas**

Default to a register containing owner, dependency, criticality, deadline, origin and status. Keep any board as a secondary view.

- [ ] **Step 6: Run module suites**

Run: `cd apps/web && npm test -- --run src/features/equipes src/features/mensagens src/features/tarefas src/features/operationalTabsInstitutional.test.ts`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/features/equipes apps/web/src/features/mensagens apps/web/src/features/tarefas apps/web/src/features/operationalTabsInstitutional.test.ts
git commit -m "style(operations): formalize teams messages and tasks"
```

### Task 8: Financeiro, Integrações and Segurança

**Files:**
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceRevenueTracePage.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.css`
- Modify: `apps/web/src/features/integracoes/IntegracoesPage.tsx`
- Modify: `apps/web/src/features/integracoes/IntegracoesPage.css`
- Modify: `apps/web/src/features/auth/DeviceSecurityPage.tsx`
- Modify: `apps/web/src/features/auth/DeviceSecurityPage.css`
- Create: `apps/web/src/features/governanceTabsInstitutional.test.ts`

**Interfaces:**
- Produces: revenue-only technical trace, integration registry and device/grant register.

- [ ] **Step 1: Write governance tab tests**

Assert Financeiro contains only `Rastreio de receita`, Integrations displays provider/scope/cursor/last result, and Security displays device/grant validity without secrets.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/features/governanceTabsInstitutional.test.ts`

Expected: FAIL.

- [ ] **Step 3: Implement dense governance surfaces**

Use tables, definition lists and evidence drawers with complete frames. Every pending command shows request ID and exact reason.

- [ ] **Step 4: Run module and policy suites**

Run: `cd apps/web && npm test -- --run src/features/financeiro src/features/integracoes src/features/auth/DeviceSecurityPage.policy.test.ts src/features/governanceTabsInstitutional.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/features/financeiro apps/web/src/features/integracoes apps/web/src/features/auth/DeviceSecurityPage.tsx apps/web/src/features/auth/DeviceSecurityPage.css apps/web/src/features/governanceTabsInstitutional.test.ts
git commit -m "style(governance): formalize revenue integrations and security"
```

### Task 9: Responsive and visual verification gate

**Files:**
- Modify only CSS/tests exposed by screenshots or overflow checks.

**Interfaces:**
- Produces: verified desktop 1440×1000 and mobile 390×844 layouts.

- [ ] **Step 1: Run full web gates**

Run: `cd apps/web && npm run lint && npm test -- --run && npm run build`

Expected: exit 0.

- [ ] **Step 2: Start production preview**

Run: `cd apps/web && npm run preview:local -- --port 4177`

Expected: preview listens on `127.0.0.1:4177` and serves the generated service worker.

- [ ] **Step 3: Capture desktop and mobile routes through Edge CDP**

Capture `/login`, `/home`, `/home?tab=memory`, `/rdos`, `/obras`, `/equipes`, `/mensagens`, `/tarefas`, `/financeiro`, `/integracoes`, `/seguranca` at 1440×1000 and 390×844.

Expected: no horizontal overflow; complete frames; yellow only in semantic roles; readable 200% zoom.

- [ ] **Step 4: Verify reduced motion and keyboard flow**

Emulate `prefers-reduced-motion: reduce`; tab through sidebar, filters, primary action, main content and profile controls.

Expected: visible focus and no essential information hidden by motion.

- [ ] **Step 5: Record and commit visual evidence**

Create `docs/checkpoints/cortex-institutional-ui-2026-07-17.md` listing screenshot paths, viewports, routes and any intentionally retained exceptions.

```bash
git add docs/checkpoints/cortex-institutional-ui-2026-07-17.md
git commit -m "docs: record institutional UI verification"
```

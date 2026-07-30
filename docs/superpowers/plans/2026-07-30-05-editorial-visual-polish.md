# Córtex 3.0 Editorial and Visual Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce redundant interface copy and regularize font weights and Obras status-badge geometry across the nine approved surfaces without changing routes, authorization, data behavior, offline recovery, or the established Córtex identity.

**Architecture:** Apply semantic, surface-local edits only after Plans 01–04 land. Lock the editorial contract in one source-policy test, keep behavior tests near components, classify formerly global bold text as an explicit 500 or 600 role, and extend the existing real-browser Obras verifier for the new dimensional contract.

**Tech Stack:** React 19, TypeScript, Vitest, Testing Library, CSS, Node geometry scripts, Chromium-family headless browser, npm workspaces.

## Global Constraints

- Run this plan last, after:
  - `docs/superpowers/plans/2026-07-30-01-rdo-universal-global-workforce-ontology.md`
  - `docs/superpowers/plans/2026-07-30-02-academy-jit-otp-access.md`
  - `docs/superpowers/plans/2026-07-30-03-obras-reactivation-timeline-actor.md`
  - `docs/superpowers/plans/2026-07-30-04-offline-transport-r2-production-proof.md`
- Recheck each file below against the merged Plans 01–04 before editing; preserve the Plan 03 reactivation action/actor line and Plan 04 per-item sync banner.
- Scope is exactly Início, Obras, RDOs, Equipes, Mensagens, active Financeiro, PDOR, Login/Ativação/Segurança do dispositivo, and Memória.
- Preserve sidebar, routes, Poppins, palette, breakpoints, authorization, real data, persistence, sync, offline behavior, provenance, and domain rules.
- Body prose stays 400; navigation, controls, labels, tabs, facts, and ordinary statuses use 500; titles, names, totals, critical/recovery/destructive alerts, conflicts, and exceptions use 600.
- Remove copy by meaning, never by element type. Preserve errors, recovery, offline state, destructive/import consequences, permission, provenance, conflict, privacy, and audit language.
- Pair every negative copy assertion with a positive assertion for nearby protected operational copy.
- Do not replace global `strong, b` with `inherit`; classify local roles first, remove the global rule only in Task 6, then smoke-test out-of-scope routes.
- Obras lifecycle badge contract: 22–24 px high, `padding-block: 2px`, `padding-inline: 7px`, 1 px border, weight 500, `nowrap`, state contrast, no overlap. It is not `.obras-sync-state`.
- Geometry commands must use each harness's `CORTEX_BROWSER_BIN`/installed-browser resolution. Do not pin a developer-specific Chrome path in this plan.
- Every task is RED → minimal implementation → GREEN → focused commit. Stop when a RED test passes unexpectedly.

## Task 1: Freeze the protected-copy baseline

**Files:**
- Create: `apps/web/src/editorialPolish.test.ts`
- Read: `docs/superpowers/specs/2026-07-30-cortex-rdo-universal-sync-academy-jit-design.md` and every production path named in Tasks 2–6

- [ ] Capture the immutable review base before the first plan-05 edit. The file is private Git metadata and must never be staged:

```bash
EDITORIAL_BASE_FILE="$(git rev-parse --git-path cortex-editorial-polish-base.sha)"
git rev-parse HEAD | tee "$EDITORIAL_BASE_FILE"
git status --short
```

- [ ] Gate the integrated Plans 01–04 on their delivered migrations and runtime entry points, not on the mere presence of plan documents:

```bash
test -f apps/api/src/main/resources/db/migration-postgresql/V65__rdo_operational_identity_and_authoritative_audit.sql
test -f apps/api/src/main/resources/db/migration/V45__rdo_operational_identity_and_authoritative_audit.sql
test -f apps/api/src/main/resources/db/migration-postgresql/V66__rdo_object_transport_integrity.sql
test -f apps/api/src/main/resources/db/migration/V46__rdo_object_transport_integrity.sql
test -f apps/api/src/main/java/com/projeto/cortex/auth/AcademyJitAccessService.java
test -f apps/api/src/main/java/com/projeto/cortex/auth/AcademySessionEligibilityGate.java
test -f apps/web/src/lib/db/authoringDb.ts
rg -n 'WORKSITE_REACTIVATE|OBRA_REATIVADA|REATIVAR_OBRA' \
  apps/api/src/main/java/com/projeto/cortex apps/web/src/features/obras
```

- [ ] Run focused prerequisite behavior/schema gates:

```bash
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw \
  -Dtest='MigrationVersionUniquenessTest,PostgresqlFoundationContractTest,PostgresqlRdoOperationalIdentityV65IT,PostgresqlStoredObjectLifecycleIT,StoredObjectLifecycleMysqlMigrationTest,StoredObjectLifecycleMysqlIntegrationTest,AcademyJitAccessServiceTest,AcademySessionEligibilityGateTest,AcademyJdbcRuntimeContractTest,ExternalSourceSchedulersTest,ObraServiceTest,ObraSyncOperationHandlerTest,OperationalMutationCoverageTest' \
  test
cd ../..
npm --prefix apps/web test -- src/lib/db/authoringDb.test.ts \
  src/lib/sync/syncTransportSession.test.ts \
  src/features/obras/obraLifecycle.test.ts
bash scripts/security/test-production-publication.sh
```

Expected: PASS with V65/V45 and V66/V46 installed, Academy JIT enabled independently from polling, reactivation covered by canonical operations, and the authoring database stable by owner/device. Stop this plan if any prerequisite gate fails.

- [ ] Create one `it(...)` per surface with narrow source reads and only positive assertions for copy that must survive:

```ts
const read = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");
it("keeps the RDO import consequence before editorial edits", () => {
  const source = read("./features/rdos/RdoCreationDialog.tsx");
  expect(source).toContain(
    "Selecione a obra sem alterar os dados importados.",
  );
});
```
- [ ] Add positive offline, denied, error, recovery, destructive, import, provenance, conflict, privacy, and audit assertions. Do not add the negative phrase assertions for Tasks 2–6 yet; each owning task adds its own RED immediately before the matching production edit.
- [ ] Run the protected-copy baseline:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts
```
Expected: PASS on the integrated Plans 01–04. Repair an incorrect inventory instead of weakening a protected assertion.
- [ ] Commit only the green protected-copy baseline:

```bash
git add apps/web/src/editorialPolish.test.ts
git commit -m "test(web): lock editorial polish contract"
```

## Task 2: Classify shared typography and polish Início/Obras

**Files:**
- Modify production: `apps/web/src/index.css`, `apps/web/src/components/shell/CortexShell.css`, `apps/web/src/components/workspace/OperationalWorkspace.css`, `apps/web/src/components/SyncStatusBanner.css`, `apps/web/src/features/home/FinanceHomeCard.tsx`, `apps/web/src/features/obras/ObrasPage.tsx`, `apps/web/src/features/obras/gestao/gestaoObras.css`, `apps/web/src/features/obras/gestao/NovaObraForm.css`, `apps/web/scripts/verify-obras-trash-geometry.mjs`
- Modify tests: `apps/web/src/uiShellPolish.test.ts`, `apps/web/src/features/home/FinanceHomeCard.test.tsx`, `apps/web/src/features/obras/obrasLayout.test.ts`, `apps/web/src/features/obras/obrasGeometry.test.ts`

- [ ] Add RED typography assertions while temporarily retaining the current global-bold expectation:

```ts
expect(
  rule(operationalWorkspaceCss, ".workspace-status-rail__state"),
).toContain("font-weight: 500;");
expect(
  rule(operationalWorkspaceCss, ".workspace-status-rail__state > strong"),
).toContain("font-weight: 500;");
expect(operationalWorkspaceCss).toMatch(
  /\.workspace-status-rail__state:is\(\[data-status="CONFLICT"\],\s*\[data-status="REJECTED"\]\)\s*>\s*strong\s*\{[^}]*font-weight:\s*600;/s,
);
expect(rule(shellCss, ".profile-menu-name")).toContain("font-weight: 600;");
expect(rule(syncCss, ".sync-chip__header")).toContain("font-weight: 600;");
expect(rule(syncCss, ".sync-chip__action")).toContain("font-weight: 500;");
expect(rule(syncCss, ".sync-chip__error")).toContain("font-weight: 600;");
expect(rule(globalCss, ".home-obra-pill")).toContain("font-weight: 500;");
```
Also invert the real `obrasLayout.test.ts` selectors `.obras-create-action` and `.nova-obra-form footer button` from 600 to 500, and add 500 assertions for `.gestao-obras button` and `.nova-obra-form label`. Keep `.gestao-obras-item-nome`, headings, and destructive/error titles at 600.

- [ ] Add editorial RED assertions:

```ts
expect(obras).not.toContain("Obras · Escopo operacional");
expect(obras).not.toContain("Filtrar por:");
```

In `FinanceHomeCard.test.tsx`, render the existing two-evidence fixture and assert the heading count `2 evidências` remains but the duplicate paragraph `2 evidências aceitas` is absent. Add a zero-evidence fixture that positively preserves `Nenhuma evidência de receita aceita nesta obra.` Keep denied/offline/error/retry states and worksite lifecycle/access language.

- [ ] Extend the existing geometry script, not a new one, with lifecycle-state fixtures and:

```js
assert.ok(height >= 22 && height <= 24);
assert.equal(paddingTop, 2);
assert.equal(paddingRight, 7);
assert.equal(paddingBottom, 2);
assert.equal(paddingLeft, 7);
assert.equal(borderTopWidth, 1);
assert.equal(fontWeight, 500);
assert.equal(whiteSpace, "nowrap");
assert.ok(!overlapsNeighbor);
```

Read those numeric values from `getComputedStyle`; add a 390 px/collapsed-sidebar scenario to the existing four and update `obrasGeometry.test.ts` to expect five verified scenarios.

- [ ] Run RED:

```bash
npm --prefix apps/web test -- src/uiShellPolish.test.ts src/editorialPolish.test.ts \
  src/features/home/FinanceHomeCard.test.tsx src/features/obras/obrasLayout.test.ts \
  src/features/obras/obrasGeometry.test.ts
```

Expected: explicit roles, positive-count prose, two Obras strings, button weight, and badge geometry fail.

- [ ] Put each role in its owning stylesheet: `OperationalWorkspace.css` gives ordinary rail state 500, explicitly overrides its `<strong>` label to 500 while the global fallback still exists, and gives only the existing `data-status="CONFLICT"|"REJECTED"` label 600; `SyncStatusBanner.css` gives header/error 600 and action/count/meta facts 500; `CortexShell.css` keeps `.profile-menu-name` 600; `gestaoObras.css` and `NovaObraForm.css` give controls/labels 500 while preserving names/headings/errors at 600. Keep global `strong, b` until Task 6.
- [ ] For a positive Financeiro result, remove only the duplicate accepted-evidence paragraph while retaining its compact heading count/link. Preserve the exact zero-evidence sentence and all non-happy states.
- [ ] Change `Obras · Escopo operacional` to `Escopo operacional`; remove only `Filtrar por:`.
- [ ] Add `data-state={focusedObra.status}` to `.home-obra-pill`, reuse lifecycle groups from `homeFilters.ts`, and set 500, 1 px, 2 × 7 px, 22–24 px, `nowrap`.
- [ ] Keep worksite names/critical alerts at 600; controls, labels, ordinary status/risk, and PDOR facts at 500.
- [ ] Run GREEN:

```bash
npm --prefix apps/web test -- src/uiShellPolish.test.ts src/editorialPolish.test.ts \
  src/features/home/FinanceHomeCard.test.tsx src/features/home/HomeOverview.sync.test.tsx \
  src/features/obras/obrasLayout.test.ts src/features/obras/obrasGeometry.test.ts \
  src/features/obras/PdorPanel.test.tsx
```

- [ ] Commit:

```bash
git add apps/web/src/index.css apps/web/src/uiShellPolish.test.ts \
  apps/web/src/components/shell/CortexShell.css \
  apps/web/src/components/workspace/OperationalWorkspace.css \
  apps/web/src/components/SyncStatusBanner.css \
  apps/web/src/editorialPolish.test.ts apps/web/src/features/home/FinanceHomeCard.tsx \
  apps/web/src/features/home/FinanceHomeCard.test.tsx apps/web/src/features/obras/ObrasPage.tsx \
  apps/web/src/features/obras/gestao/gestaoObras.css \
  apps/web/src/features/obras/gestao/NovaObraForm.css \
  apps/web/src/features/obras/obrasLayout.test.ts apps/web/scripts/verify-obras-trash-geometry.mjs \
  apps/web/src/features/obras/obrasGeometry.test.ts
git commit -m "style(web): polish home and obras hierarchy"
```

## Task 3: Remove repeated RDO guidance without weakening consequences

**Files:**
- Modify production: `apps/web/src/features/rdos/RdoCreationDialog.tsx`, `apps/web/src/features/rdos/RdoCreatePage.tsx`, `apps/web/src/features/rdos/RdoLocalList.tsx`, `apps/web/src/features/rdos/RdoWorkspacePage.css`, `apps/web/src/features/rdos/RdoCreationDialog.css`
- Modify tests: `apps/web/src/features/rdos/RdoCreationDialog.test.tsx`, `apps/web/src/features/rdos/RdoCreatePage.workforceContext.test.tsx`, `apps/web/src/features/rdos/RdoLocalList.filters.test.tsx`

- [ ] Invert current helper expectations and add RED assertions:

```ts
expect(dialog).not.toContain("Córtex · RDO");
expect(dialog).not.toContain("Selecione a obra que dará origem");
expect(createPage).not.toContain(
  "Definido automaticamente pela obra selecionada.",
);
expect(createPage).not.toContain(
  "O número é preenchido automaticamente.",
);
expect(localList).not.toContain("Registro central");
```

Extend `renderDialog` to accept `initialDraft`, render it with `createEmptyRdo()`, and positively assert the exact protected consequence `Selecione a obra sem alterar os dados importados.` delivered by Plan 01. In `RdoCreatePage.workforceContext.test.tsx`, keep the real `readonly`/`aria-readonly="true"` assertions for the Obra ID and Número do RDO inputs while rejecting only the two helper sentences above. In `RdoLocalList.filters.test.tsx`, reject `Ajuste os filtros ou crie um RDO para iniciar a sequência operacional.` but preserve the `Nenhum RDO encontrado` heading and `Criar RDO` button. With a record/event fixture, assert `.rdo-operational-card .rdo-card-facts` no longer contains the duplicate `Eventos` fact; open the record profile and assert its `<dt>Eventos</dt>` summary remains. Positively assert offline, catalog-unavailable, attachment warning, and error copy.

- [ ] Run RED:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts \
  src/features/rdos/RdoCreationDialog.test.tsx \
  src/features/rdos/RdoCreatePage.workforceContext.test.tsx \
  src/features/rdos/RdoLocalList.filters.test.tsx src/features/rdos/rdoInstitutionalLayout.test.ts
```

Expected: kicker, standard selection helper, two readonly helper sentences, list instruction, `Registro central`, and the duplicate card event fact fail.

- [ ] Remove the dialog kicker/standard sentence but keep the conditional imported-data consequence.
- [ ] Remove the two readonly `<small>` helpers; keep readonly affordances and permission feedback.
- [ ] Keep the empty heading/action; remove its filter/create instruction, `Registro central`, and the repeated per-card event fact.
- [ ] Set selections/options/index/status/controls/metadata to 500; headings, RDO/attachment titles, catalog-unavailable, and errors to 600.
- [ ] Run GREEN plus geometry:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts \
  src/features/rdos/RdoCreationDialog.test.tsx src/features/rdos/RdoCreatePage.workforceContext.test.tsx \
  src/features/rdos/RdoLocalList.filters.test.tsx src/features/rdos/rdoInstitutionalLayout.test.ts \
  src/features/rdos/rdoCreationGeometry.test.ts
```

- [ ] Commit:

```bash
git add apps/web/src/editorialPolish.test.ts apps/web/src/features/rdos/RdoCreationDialog.tsx \
  apps/web/src/features/rdos/RdoCreationDialog.test.tsx apps/web/src/features/rdos/RdoCreatePage.tsx \
  apps/web/src/features/rdos/RdoCreatePage.workforceContext.test.tsx \
  apps/web/src/features/rdos/RdoLocalList.tsx apps/web/src/features/rdos/RdoLocalList.filters.test.tsx \
  apps/web/src/features/rdos/RdoWorkspacePage.css apps/web/src/features/rdos/RdoCreationDialog.css
git commit -m "style(web): tighten rdo operational copy"
```

## Task 4: Polish Equipes and Mensagens without losing scope/offline signals

**Files:**
- Modify production: `apps/web/src/features/equipes/EquipesPage.tsx`, `apps/web/src/features/equipes/EquipesPage.css`, `apps/web/src/features/mensagens/components/ConversationInfoPane.tsx`, `apps/web/src/features/mensagens/components/CreateConversationDialog.tsx`, `apps/web/src/features/mensagens/MensagensPage.css`
- Modify tests: `apps/web/src/features/equipes/EquipesPage.sync.test.tsx`, `apps/web/src/features/mensagens/components/CreateConversationDialog.test.tsx`; create `apps/web/src/features/mensagens/components/ConversationInfoPane.test.tsx`

- [ ] Add RED negatives for `Revise os filtros`, detail instruction, three Equipes kickers, `Participação temporal`, info-pane generic intro/duplicate scope/count, and conversation-directory instruction.
- [ ] Positively require `Somente Alfa`, `Administração Alfa`, archive/end consequences/reasons, privacy/permission feedback, and thread offline text.
- [ ] Create `ConversationInfoPane.test.tsx`: render the structural `<aside>`, verify its `aria-hidden` state, and reject generic/duplicate prose.
- [ ] Run RED:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts \
  src/features/equipes/EquipesPage.sync.test.tsx \
  src/features/mensagens/components/ConversationInfoPane.test.tsx \
  src/features/mensagens/components/CreateConversationDialog.test.tsx \
  src/features/mensagens/mensagensLayout.test.ts
```

Expected: negative assertions fail while protected scope, consequence, reason, privacy, and offline assertions pass.

- [ ] Remove only those Equipes helpers/kickers; preserve access, destructive, actor, and recovery language.
- [ ] Keep the info-pane aside/accessibility state; remove its generic intro, duplicate scope, and duplicate participant count.
- [ ] Remove only the create-dialog directory instruction; preserve search, privacy, permission, validation, and offline feedback.
- [ ] Use 500 for status/access facts, overview data, info lines, counts/options/controls; 600 for names, headings, audits, empty/danger titles, conversation/person/document identities.
- [ ] Run GREEN:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts \
  src/features/equipes/EquipesPage.sync.test.tsx \
  src/features/mensagens/components/ConversationInfoPane.test.tsx \
  src/features/mensagens/components/CreateConversationDialog.test.tsx \
  src/features/mensagens/mensagensLayout.test.ts
npm --prefix apps/web run verify:mensagens-geometry
```

- [ ] Commit:

```bash
git add apps/web/src/editorialPolish.test.ts apps/web/src/features/equipes/EquipesPage.tsx \
  apps/web/src/features/equipes/EquipesPage.css apps/web/src/features/equipes/EquipesPage.sync.test.tsx \
  apps/web/src/features/mensagens/components/ConversationInfoPane.tsx \
  apps/web/src/features/mensagens/components/ConversationInfoPane.test.tsx \
  apps/web/src/features/mensagens/components/CreateConversationDialog.tsx \
  apps/web/src/features/mensagens/components/CreateConversationDialog.test.tsx \
  apps/web/src/features/mensagens/MensagensPage.css
git commit -m "style(web): clarify equipes and mensagens surfaces"
```

## Task 5: Polish active Financeiro and classify PDOR

**Files:**
- Modify production: `apps/web/src/features/financeiro/FinanceiroPage.tsx`, `apps/web/src/features/financeiro/FinanceiroPage.css`, `apps/web/src/features/financeiro/FinanceRevenueTracePage.tsx`, `apps/web/src/features/financeiro/ServicePriceCatalogPage.tsx`
- Modify tests: `apps/web/src/features/financeiro/FinanceiroPage.test.tsx`, `apps/web/src/features/financeiro/FinanceRevenueTracePage.test.tsx`, `apps/web/src/features/financeiro/ServicePriceCatalogPage.test.tsx`, `apps/web/src/features/obras/PdorPanel.test.tsx`

- [ ] Add RED negatives for the paragraph duplicating the active Financeiro heading, the revenue-trace intro, and the catalog kicker.
- [ ] Positively require the heading, revenue rule, provenance/audit trail, catalog authority paragraph, offline trace state, and all PDOR operational copy.
- [ ] Run RED:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts \
  src/features/financeiro/FinanceiroPage.test.tsx \
  src/features/financeiro/FinanceRevenueTracePage.test.tsx \
  src/features/financeiro/ServicePriceCatalogPage.test.tsx \
  src/features/financeiro/FinanceRevenueTraceOffline.test.tsx \
  src/features/financeiro/FinancePdorSection.test.tsx \
  src/features/financeiro/FinanceRevenueUiPolicy.test.ts src/features/obras/PdorPanel.test.tsx
```

Expected: only three redundant active-route strings and unclassified explicit weights fail.

- [ ] Remove those three strings. Preserve rule, provenance, audit, catalog-authority, offline, and all PDOR copy.
- [ ] Do not edit dormant legacy Financeiro panels.
- [ ] Set controls/labels/status/provenance/table data to 500; headings, worksite identity, totals, alerts, and item titles to 600.
- [ ] Rerun the RED command to GREEN.
- [ ] Commit:

```bash
git add apps/web/src/editorialPolish.test.ts apps/web/src/features/financeiro/FinanceiroPage.tsx \
  apps/web/src/features/financeiro/FinanceiroPage.css apps/web/src/features/financeiro/FinanceiroPage.test.tsx \
  apps/web/src/features/financeiro/FinanceRevenueTracePage.tsx \
  apps/web/src/features/financeiro/FinanceRevenueTracePage.test.tsx \
  apps/web/src/features/financeiro/ServicePriceCatalogPage.tsx \
  apps/web/src/features/financeiro/ServicePriceCatalogPage.test.tsx \
  apps/web/src/features/obras/PdorPanel.test.tsx
git commit -m "style(web): refine finance and pdor hierarchy"
```

## Task 6: Finish Auth/Memória and remove the global bold fallback

**Files:**
- Modify production: `apps/web/src/features/auth/LoginPage.tsx`, `apps/web/src/features/auth/LoginPage.css`, `apps/web/src/features/home/memory/MemoryLedger.css`, `apps/web/src/index.css`
- Modify tests: `apps/web/src/features/auth/LoginPage.behavior.test.tsx`, `apps/web/src/features/home/memory/MemoryLedger.test.tsx`, `apps/web/src/uiShellPolish.test.ts`

- [ ] Add RED negatives for the Login eyebrow/generic subtitle and positive offline/failure/recovery/credential-hint assertions. Assert Memória loses no copy.
- [ ] Invert the final global-selector expectation:

```ts
expect(indexCss).not.toMatch(/strong\s*,\s*b\s*\{[^}]*font-weight:\s*600/s);
expect(indexCss).not.toMatch(/strong\s*,\s*b\s*\{[^}]*font-weight:\s*inherit/s);
```

- [ ] Run RED:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts src/uiShellPolish.test.ts \
  src/features/auth/LoginPage.behavior.test.tsx src/features/auth/LoginPage.authPolicy.test.ts \
  src/features/auth/DeviceSecurityPage.policy.test.ts src/features/auth/ActivationPage.test.tsx \
  src/features/home/memory/MemoryLedger.test.tsx
```

Expected: Login copy and global 600 fallback fail; protected Auth/Memória assertions pass.

- [ ] Remove only the Login eyebrow/subtitle. Keep offline, validation, credentials, error, retry, activation, and device-security language.
- [ ] Set auth controls/labels/status to 500 and headings/alerts to 600; set Memória coverage/entity/commit facts to 500 and notice/empty/review titles to 600.
- [ ] Audit every semantic emphasis before deleting the global rule:

```bash
rg -n '<(strong|b)(\s|>)' apps/web/src
rg -n 'font-weight:\s*(500|600)' apps/web/src/index.css apps/web/src/features
```

- [ ] Add any missing surface-local selector, then remove global `strong, b { font-weight: 600 }`; never add a global `inherit`.
- [ ] Run GREEN plus Memória geometry:

```bash
npm --prefix apps/web test -- src/editorialPolish.test.ts src/uiShellPolish.test.ts \
  src/features/auth/LoginPage.behavior.test.tsx src/features/auth/LoginPage.authPolicy.test.ts \
  src/features/auth/DeviceSecurityPage.policy.test.ts src/features/auth/ActivationPage.test.tsx \
  src/features/home/memory/MemoryLedger.test.tsx src/features/home/memory/memoryGeometry.test.ts
```

- [ ] Commit:

```bash
git add apps/web/src/editorialPolish.test.ts apps/web/src/features/auth/LoginPage.tsx \
  apps/web/src/features/auth/LoginPage.css apps/web/src/features/auth/LoginPage.behavior.test.tsx \
  apps/web/src/features/home/memory/MemoryLedger.css \
  apps/web/src/features/home/memory/MemoryLedger.test.tsx apps/web/src/index.css \
  apps/web/src/uiShellPolish.test.ts
git commit -m "style(web): complete editorial typography polish"
```

## Task 7: Full verification and browser acceptance

- [ ] Run focused cross-surface tests:

```bash
npm --prefix apps/web test -- src/uiShellPolish.test.ts src/editorialPolish.test.ts \
  src/features/home/FinanceHomeCard.test.tsx src/features/obras/obrasLayout.test.ts \
  src/features/obras/PdorPanel.test.tsx src/features/rdos/RdoCreatePage.workforceContext.test.tsx \
  src/features/rdos/RdoCreationDialog.test.tsx src/features/rdos/RdoLocalList.filters.test.tsx \
  src/features/equipes/EquipesPage.sync.test.tsx \
  src/features/mensagens/components/CreateConversationDialog.test.tsx \
  src/features/mensagens/components/ConversationInfoPane.test.tsx \
  src/features/financeiro/FinanceiroPage.test.tsx src/features/financeiro/FinancePdorSection.test.tsx \
  src/features/auth/LoginPage.behavior.test.tsx src/features/home/memory/MemoryLedger.test.tsx
```

- [ ] Run real-browser geometry:

```bash
npm --prefix apps/web test -- src/features/obras/obrasGeometry.test.ts \
  src/features/rdos/rdoCreationGeometry.test.ts src/features/home/memory/memoryGeometry.test.ts
npm --prefix apps/web run verify:mensagens-geometry
npm --prefix apps/web run verify:operational-layout
```

- [ ] Run full gates:

```bash
npm --prefix apps/web test
npm --prefix apps/web run lint
npm --prefix apps/web run build
```

- [ ] Start the real app against the intended API/profile and inspect all nine surfaces at 390, 1100, and 1440 px: real data/names, stable controls, no truncation/overlap.
- [ ] Smoke-test out-of-scope Tarefas, Integrações, importers, and admin routes after the global bold removal.
- [ ] Inspect every lifecycle badge state for contrast/geometry and visually distinguish it from sync status.
- [ ] Exercise offline, denied, error, retry, destructive, import, provenance, conflict, privacy, and recovery states; record unavailable states as unverified.
- [ ] Review the complete plan-05 range from the SHA captured in Task 1, plus any still-uncommitted fix:

```bash
EDITORIAL_BASE_FILE="$(git rev-parse --git-path cortex-editorial-polish-base.sha)"
EDITORIAL_BASE_SHA="$(sed -n '1p' "$EDITORIAL_BASE_FILE")"
git rev-parse --verify "$EDITORIAL_BASE_SHA^{commit}"
git diff --check "$EDITORIAL_BASE_SHA"..HEAD
git diff --stat "$EDITORIAL_BASE_SHA"..HEAD
git log --oneline "$EDITORIAL_BASE_SHA"..HEAD
git diff --check
git status --short
```

- [ ] Do not claim production/visual acceptance from unit tests. If verification requires scoped fixes, stage only their exact paths already listed in Tasks 1–6; do not create an empty verification commit.

## Risks and Stop Conditions

- Stop if removal of an empty-state helper leaves its action invisible at any breakpoint.
- Stop if the conditional RDO import consequence disappears, even when generic dialog copy is gone.
- Stop if Obras badge values/colors derive from sync state rather than lifecycle state.
- Stop if a broad Financeiro edit reaches dormant legacy panels.
- Treat loss of Equipes/Mensagens access, archive/end, actor, privacy, or offline language as a regression.
- Static tests do not prove Teams, Financeiro, or Auth geometry; their browser review is mandatory.
- Do not report completion until focused/full tests, lint, build, geometry gates, and real-data browser review are recorded separately.

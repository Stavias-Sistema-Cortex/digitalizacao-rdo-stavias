# Cortex 2.1 Memory Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the authenticated Cortex into a sober institutional platform and make Home → Memória the exclusive, authorized ledger of ontology modifications.

**Architecture:** Add a paginated `/api/ontology/memory` read model over `cortex_evento_operacional`, scoped through `CurrentUserService.allowedObraIds`. The web Home splits into URL-addressable Overview and Memory tabs; the ledger merges authorized server events with honest device-local events, while other domains retain operational history but link ontology auditing into Memory instead of rendering duplicate event lists.

**Tech Stack:** Java 21, Spring Boot 3.3, JdbcTemplate, JUnit 5/Mockito, React 19, TypeScript 6, React Router 7, IndexedDB/idb, Vitest 4, CSS.

## Global Constraints

- Preserve the existing sidebar, routes, Poppins files, StavIA launcher, StavIA panel, auth, sync, offline, PDOR, messaging, tasks, finance, and integration behavior.
- Use `#111312` for institutional black, `#292d2b` for graphite, `#f1f3f0` for canvas, `#ffffff` for surfaces, `#cfd4d0` for borders, `#124e4a` for Cortex teal, and `#f2c800` for Stavias yellow.
- Use font weights 400–500 for body/control copy, 600 for headings, and 700 only for key metrics, alerts, or brand identity.
- Use radii of 4px for controls and dense rows and 6px for major containers; full pills remain restricted to status.
- Do not infer actors or audit fields. Missing server actors render as “Processo do sistema”.
- Alfa has global server scope. Beta is restricted to active authorized worksites plus self-authored global events.
- Preserve `/api/ontology/timeline`; the new cursor contract lives at `/api/ontology/memory`.
- Server order is `commit_seq DESC`; `beforeCommitSeq` is exclusive; default limit is 50 and maximum is 100.
- Ontology event lists render only under Home → Memória. Domain state history may remain elsewhere.
- Use JDK 21 for Maven commands.
- The known baseline API error is `EmailConfigurationTest.applicationUsesAuthoritativeSmtpEnvironmentNames` because `docs/superpowers/plans/2026-07-13-auth-security-and-finance-permissions.md` is absent on `develop`; do not attribute it to this feature.

---

## File Structure

### API

- Create `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryFilter.java`: normalized query contract.
- Create `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryScope.java`: Alfa/Beta scope value.
- Create `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryEventResponse.java`: complete authorized event DTO.
- Create `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryPageResponse.java`: cursor page envelope.
- Create `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryQueryService.java`: SQL, mapping, pagination.
- Create `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryController.java`: auth and request boundary.
- Create `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMemoryQueryServiceTest.java`.
- Create `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMemoryControllerAuthorizationMockMvcTest.java`.

### Web memory feature

- Create `apps/web/src/features/home/memory/memory.types.ts`: server/local/view types.
- Create `apps/web/src/features/home/memory/memoryApi.ts`: query serialization and response normalization.
- Create `apps/web/src/features/home/memory/memoryViewModel.ts`: labels, authorized local merge, diff rows.
- Create `apps/web/src/features/home/memory/useMemoryLedger.ts`: server/local loading and pagination.
- Create `apps/web/src/features/home/memory/MemoryLedger.tsx`: filter bar, coverage, ledger, detail rows.
- Create `apps/web/src/features/home/memory/MemoryLedger.css`: responsive institutional ledger.
- Create `apps/web/src/features/home/memory/memoryApi.test.ts`.
- Create `apps/web/src/features/home/memory/memoryViewModel.test.ts`.

### Home and centralization

- Create `apps/web/src/features/home/HomeSubnav.tsx`: accessible URL-addressable tabs.
- Create `apps/web/src/features/home/HomeOverview.tsx`: current dashboard composition.
- Create `apps/web/src/features/home/MemorySummaryCard.tsx`: no audit list, only coverage/action.
- Create `apps/web/src/features/home/homeTab.ts` and `homeTab.test.ts`: canonical tab/query handling.
- Modify `apps/web/src/features/home/HomePage.tsx`: subtab orchestration.
- Delete `apps/web/src/features/home/AtualizacoesCard.tsx` after all imports are removed.
- Modify `apps/web/src/features/rdos/RdoLocalList.tsx`: remove event list and add filtered Memory links.
- Modify `apps/web/src/features/obras/ObrasPage.tsx`: remove Cortex trace event list and fetch.
- Modify `apps/web/src/features/obras/obrasApi.ts` and `obrasApi.test.ts`: remove obsolete timeline client while preserving PDOR.
- Modify `apps/web/src/features/equipes/EquipesPage.tsx`: remove ontology audit list and add filtered Memory link; preserve member history.
- Create `apps/web/src/features/home/memory/memoryLocation.ts` and `.test.ts`: filtered URLs used by domain tabs.

### Institutional visual system

- Modify `apps/web/src/index.css`: global tokens, Home/RDO/Obras, shared controls, motion, responsive rules.
- Modify `apps/web/src/features/equipes/EquipesPage.css`.
- Modify `apps/web/src/features/mensagens/MensagensPage.css`.
- Modify `apps/web/src/features/tarefas/TarefasPage.css`.
- Modify `apps/web/src/features/financeiro/FinanceiroPage.css`.
- Modify `apps/web/src/features/integracoes/IntegracoesPage.css`.
- Create `apps/web/src/features/home/institutionalUiPolicy.test.ts`: token, weight, radius, reduced-motion, and StavIA preservation policy.

---

### Task 1: Authorized cursor-paginated memory API

**Files:**
- Create all six API production files listed above.
- Test: `OperationalMemoryQueryServiceTest.java`.
- Test: `OperationalMemoryControllerAuthorizationMockMvcTest.java`.

**Interfaces:**
- Produces: `GET /api/ontology/memory`.
- Produces: `OperationalMemoryPageResponse(events, nextBeforeCommitSeq, hasMore, scope, serverTime)`.
- Consumes: `CurrentUserService.requireUserId()`, `allowedObraIds(userId)`, `requireWorksiteAccess`, `requireRdoAccess`.

- [ ] **Step 1: Write failing query-service tests**

Cover the exact filter and page behavior:

```java
@Test
void betaQueryRestrictsAuthorizedWorksitesAndSelfGlobalEvents() {
    OperationalMemoryScope scope = OperationalMemoryScope.beta(
            "beta-1", Set.of("obra-1", "obra-2")
    );
    service.memory(scope, OperationalMemoryFilter.empty(), 50, null);
    assertThat(capturedSql()).contains("e.obra_id IN (?, ?)");
    assertThat(capturedSql()).contains("e.obra_id IS NULL AND e.usuario_id = ?");
    assertThat(capturedParameters()).contains("obra-1", "obra-2", "beta-1", 51);
}

@Test
void cursorIsExclusiveAndPageUsesLimitPlusOne() {
    service.memory(OperationalMemoryScope.alfa("alfa"),
            OperationalMemoryFilter.empty(), 2, 99L);
    assertThat(capturedSql()).contains("e.commit_seq < ?");
    assertThat(capturedSql()).contains("ORDER BY e.commit_seq DESC LIMIT ?");
    assertThat(capturedParameters()).endsWith(99L, 3);
}
```

Map a mocked row with `usuario_id`, actor name, source, device, correlation,
causation, previous/new state, result, error, payload and related entities; assert
that no field is discarded and that a third row yields `hasMore=true` with two
returned events.

- [ ] **Step 2: Run query-service tests and verify RED**

Run:

```bash
cd apps/api
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw test -q -Dtest=OperationalMemoryQueryServiceTest
```

Expected: compilation failure because the memory read model does not exist.

- [ ] **Step 3: Implement the read model**

Use these records:

```java
public record OperationalMemoryScope(
        String userId,
        boolean global,
        Set<String> allowedObraIds
) {
    public static OperationalMemoryScope alfa(String userId) {
        return new OperationalMemoryScope(userId, true, Set.of());
    }
    public static OperationalMemoryScope beta(String userId, Set<String> ids) {
        return new OperationalMemoryScope(userId, false, Set.copyOf(ids));
    }
    public String label() {
        return global ? "GLOBAL" : "AUTHORIZED_WORKSITES";
    }
}
```

`OperationalMemoryQueryService.memory(...)` must:

1. select `limit + 1` rows;
2. join `colaborador actor ON actor.id = e.usuario_id`;
3. bind every user-provided value as a parameter;
4. apply Beta scope before optional filters;
5. parse invalid JSON to empty object/array without inventing values;
6. return only `limit` rows and use the last returned commit as the next cursor.

- [ ] **Step 4: Run query-service tests and verify GREEN**

Expected: all tests in `OperationalMemoryQueryServiceTest` pass.

- [ ] **Step 5: Write failing controller authorization tests**

```java
@Test
void betaWithoutExplicitWorksiteGetsAuthorizedAggregate() throws Exception {
    when(currentUser.requireUserId()).thenReturn("beta");
    when(currentUser.allowedObraIds("beta"))
            .thenReturn(Optional.of(Set.of("obra-1")));
    mockMvc.perform(get("/api/ontology/memory"))
            .andExpect(status().isOk());
    verify(service).memory(
            eq(OperationalMemoryScope.beta("beta", Set.of("obra-1"))),
            any(), eq(50), isNull()
    );
}

@Test
void explicitUnauthorizedWorksiteIsRejected() throws Exception {
    when(currentUser.requireUserId()).thenReturn("beta");
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(currentUser).requireWorksiteAccess("obra-x");
    mockMvc.perform(get("/api/ontology/memory").param("obraId", "obra-x"))
            .andExpect(status().isForbidden());
    verifyNoInteractions(service);
}
```

Also cover Alfa global, authorized explicit worksite, RDO authorization, limit
clamping, and all filter parameters.

- [ ] **Step 6: Run controller tests and verify RED**

Expected: compilation failure because `OperationalMemoryController` is absent.

- [ ] **Step 7: Implement controller and verify GREEN**

The controller creates Alfa/Beta scope from `allowedObraIds`. It calls
`requireWorksiteAccess` and `requireRdoAccess` before the service when explicit
IDs are present. Run both new API test classes together; expected PASS.

- [ ] **Step 8: Commit Task 1**

```bash
git add apps/api/src/main/java/com/projeto/cortex/ontology \
  apps/api/src/test/java/com/projeto/cortex/ontology
git commit -m "feat(api): add authorized ontology memory ledger"
```

---

### Task 2: Web memory model, API client, and local merge

**Files:**
- Create `memory.types.ts`, `memoryApi.ts`, `memoryViewModel.ts`.
- Test: `memoryApi.test.ts`, `memoryViewModel.test.ts`.

**Interfaces:**
- Produces: `MemoryEvent`, `MemoryPage`, `MemoryFilters`.
- Produces: `fetchMemoryPage(filters, beforeCommitSeq?)`.
- Produces: `mergeMemoryEvents(server, local, allowedObraIds, userId)`.
- Produces: `memoryEventLabel`, `memoryDiffRows`, `memoryQueryString`.

- [ ] **Step 1: Write failing mapper/query tests**

```ts
it("serializa filtros estruturais sem valores vazios", () => {
  expect(memoryQueryString({ obraId: "obra-1", eventType: "", limit: 50 }, 90))
    .toBe("obraId=obra-1&limit=50&beforeCommitSeq=90");
});

it("preserva ator e auditoria sem inferir campos ausentes", () => {
  expect(memoryEventFromApi({ id: "e1", commitSeq: 8, type: "RDO_CRIADO",
    actorId: null, actorName: null, payload: {}, relatedEntities: [] }))
    .toMatchObject({ actorId: null, actorName: null, actorLabel: "Processo do sistema" });
});
```

- [ ] **Step 2: Run Vitest and verify RED**

Run `npm test -- memoryApi memoryViewModel`; expected import/module failures.

- [ ] **Step 3: Implement minimal types/client/view model**

Use an explicit source discriminant:

```ts
export type MemoryEventSource = "SERVER" | "DEVICE";
export interface MemoryEvent {
  id: string;
  commitSeq: number | null;
  sourceKind: MemoryEventSource;
  type: string;
  actorId: string | null;
  actorName: string | null;
  actorLabel: string;
  obraId: string | null;
  rdoId: string | null;
  principalEntity: MemoryEntityRef;
  relatedEntities: MemoryEntityRef[];
  occurredAt: string | null;
  origin: string | null;
  syncStatus: string | null;
  previousState: Record<string, unknown>;
  newState: Record<string, unknown>;
  payload: Record<string, unknown>;
  result: string | null;
}
```

`mergeMemoryEvents` includes local events only when `obraId` is authorized, or
when `obraId` is null and `responsibleUserId === userId`; identical IDs keep the
server event. Device-only events sort before committed events by occurrence
time but retain `commitSeq=null`.

- [ ] **Step 4: Verify GREEN and commit Task 2**

Run `npm test -- memoryApi memoryViewModel`; expected PASS.

```bash
git add apps/web/src/features/home/memory
git commit -m "feat(web): model ontology memory events"
```

---

### Task 3: Home subtabs and Memory ledger UI

**Files:**
- Create `homeTab.ts`, `homeTab.test.ts`, `HomeSubnav.tsx`, `HomeOverview.tsx`,
  `MemorySummaryCard.tsx`, `useMemoryLedger.ts`, `MemoryLedger.tsx`,
  `MemoryLedger.css`.
- Modify `HomePage.tsx`.
- Delete `AtualizacoesCard.tsx`.

**Interfaces:**
- Produces: `/home?tab=overview` and `/home?tab=memory`.
- Consumes: `useHomeData`, `useStaviaLauncher`, memory functions from Task 2.

- [ ] **Step 1: Write failing home-tab tests**

```ts
it("defaults invalid values to overview", () => {
  expect(homeTabFromSearch(new URLSearchParams("tab=unknown"))).toBe("overview");
});

it("preserves memory filters when selecting memory", () => {
  expect(searchForHomeTab(new URLSearchParams("obraId=o1"), "memory").toString())
    .toBe("obraId=o1&tab=memory");
});
```

- [ ] **Step 2: Verify RED, implement helper, verify GREEN**

Run `npm test -- homeTab`; expected FAIL then PASS.

- [ ] **Step 3: Implement accessible subnav and overview extraction**

`HomeSubnav` uses `role="tablist"`; each button uses `role="tab"`,
`aria-selected`, and updates `useSearchParams`. `HomePage` renders exactly one
`role="tabpanel"`. Move the current dashboard markup without changing its data
behavior into `HomeOverview`.

- [ ] **Step 4: Implement Memory ledger container/view**

`useMemoryLedger` loads local events immediately, fetches server pages only when
online, exposes `{ events, hasMore, loadMore, reload, coverage, error,
isInitialLoading, isLoadingMore }`, and resets the cursor on structured filters.

`MemoryLedger` renders the required filters, coverage bar, ledger rows,
expandable before/after diff, technical details, explicit offline state, and a
button for earlier records. No infinite scroll.

- [ ] **Step 5: Replace Updates list with summary-only navigation**

`MemorySummaryCard` may show counts and last known commit but may not map/render
event rows. Delete `AtualizacoesCard.tsx`.

- [ ] **Step 6: Run web tests/build and commit Task 3**

Run `npm test -- homeTab memoryApi memoryViewModel`, then `npm run build`.
Expected: PASS and build exit 0.

```bash
git add apps/web/src/features/home
git commit -m "feat(web): add Home memory ledger tab"
```

---

### Task 4: Make Memory the exclusive ontology-audit surface

**Files:**
- Create `memoryLocation.ts`, `memoryLocation.test.ts`.
- Modify RDO, Obras, Equipes files listed in File Structure.

**Interfaces:**
- Produces: `memoryHref({ obraId?, rdoId?, entityType?, entityId? })`.

- [ ] **Step 1: Write failing URL tests**

```ts
expect(memoryHref({ obraId: "obra 1", entityType: "OBRA", entityId: "obra 1" }))
  .toBe("/home?tab=memory&obraId=obra+1&entityType=OBRA&entityId=obra+1");
```

Run `npm test -- memoryLocation`; expected module failure.

- [ ] **Step 2: Implement URL helper and verify GREEN**

Use `URLSearchParams`; omit blank values; always set `tab=memory` first.

- [ ] **Step 3: Remove duplicate audit lists**

- RDO: remove `timeline-panel` and the profile timeline list; retain event data
  only where required for filters/metrics/domain state. Add “Ver na Memória”
  links filtered by RDO/obra.
- Obras: remove `buscarTimelineObra`, remote timeline state/effect,
  `Rastreabilidade Cortex`, payload summary helpers used only there, and the
  obsolete timeline API contract. Add one filtered Memory link in the detail
  header; keep PDOR.
- Equipes: remove audit rendering and helpers used only by it. Preserve remote
  team history hydration and former-member history. Add filtered Memory link.

- [ ] **Step 4: Prove exclusivity with source search**

Run:

```bash
rg -n "Rastreabilidade Cortex|Histórico ontológico|timeline-panel|Atualizações" \
  apps/web/src/features --glob "*.tsx"
```

Expected: no ontology audit surface outside `features/home/memory`; domain copy
that is not an event list must be reviewed rather than blindly deleted.

- [ ] **Step 5: Run full web tests and commit Task 4**

Run `npm test`; expected 0 failures.

```bash
git add apps/web/src/features/home/memory apps/web/src/features/rdos \
  apps/web/src/features/obras apps/web/src/features/equipes
git commit -m "refactor(web): centralize ontology history in Memory"
```

---

### Task 5: Institutional tokens and Home/RDO/Obras visual system

**Files:**
- Modify `apps/web/src/index.css`.
- Test: `institutionalUiPolicy.test.ts`.

- [ ] **Step 1: Write failing CSS policy test**

```ts
const css = readFileSync(resolve(process.cwd(), "src/index.css"), "utf8");
expect(css).toContain("--color-ink: #111312");
expect(css).toContain("--radius-control: 4px");
expect(css).toContain("--radius-container: 6px");
expect(css).toContain("@media (prefers-reduced-motion: reduce)");
expect(css).not.toMatch(/\.stavia-launcher\s*\{[^}]*border-radius:\s*4px/s);
```

Run `npm test -- institutionalUiPolicy`; expected FAIL on missing Cortex 2.1 tokens.

- [ ] **Step 2: Implement global tokens and shared controls**

Add the exact tokens from Global Constraints. Set shared button/input/container
radii through variables, lower label/headline weights, and use 160ms functional
transitions. Do not edit `StaviaPanel.css` or launcher-specific geometry.

- [ ] **Step 3: Restyle Home, RDO, and Obras**

- Home: black structural subnav, flat modules, 6px containers.
- RDO: white metric registry with black/yellow accents; denser rows; 4px actions.
- Obras: flatter catalog/detail, bordered facts, dossier-style PDOR.

Remove CSS selectors belonging only to deleted audit lists.

- [ ] **Step 4: Verify policy and build, then commit**

Run `npm test -- institutionalUiPolicy` and `npm run build`; expected PASS.

```bash
git add apps/web/src/index.css apps/web/src/features/home/institutionalUiPolicy.test.ts
git commit -m "style(web): establish Cortex 2.1 visual system"
```

---

### Task 6: Apply the institutional system to every remaining tab

**Files:**
- Modify Equipes, Mensagens, Tarefas, Financeiro, and Integrações CSS files.

- [ ] **Step 1: Equipes**

Replace 9–11px decorative radii with 4–6px, reduce 700/750 weights where they
do not encode hierarchy, change catalog selection to black text/border plus a
short yellow marker, and preserve member/status semantics.

- [ ] **Step 2: Mensagens**

Keep the three-pane layout; darken structural dividers, square avatars and
composer controls, reduce bubble radii/weights, and preserve message grouping,
attachments, unread states, search, and context drawer.

- [ ] **Step 3: Tarefas**

Turn team chips into underline tabs, tasks into compact work rows, keep semantic
priority colors, and reserve yellow for active/action emphasis. Do not change
task persistence or event creation.

- [ ] **Step 4: Financeiro**

Align local finance variables to global tokens, reduce large heading weights,
set 4/6px radii, and preserve every section, permission, filter and domain
history surface.

- [ ] **Step 5: Integrações**

Make the table a service registry with black header rule, lighter body weights,
tabular operational values, 4px action controls and 6px report container.

- [ ] **Step 6: Run lint/build/full web tests and commit**

Run `npm test`, `npm run lint`, and `npm run build`. Expected: tests/build pass;
lint must have no new errors, with any preexisting finding listed explicitly.

```bash
git add apps/web/src/features/equipes/EquipesPage.css \
  apps/web/src/features/mensagens/MensagensPage.css \
  apps/web/src/features/tarefas/TarefasPage.css \
  apps/web/src/features/financeiro/FinanceiroPage.css \
  apps/web/src/features/integracoes/IntegracoesPage.css
git commit -m "style(web): institutionalize authenticated workspaces"
```

---

### Task 7: Full verification and visual QA

**Files:**
- Modify only files required by defects proven in this task.

- [ ] **Step 1: Run targeted API tests**

```bash
cd apps/api
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw test -q -Dtest=OperationalMemoryQueryServiceTest,OperationalMemoryControllerAuthorizationMockMvcTest,OperationalTimelineControllerAuthorizationMockMvcTest
```

Expected: 0 failures/errors.

- [ ] **Step 2: Run the full API suite**

Run `./mvnw test -q` under Java 21. Expected current baseline: either fully
green if the missing historical doc was restored independently, or exactly the
known `EmailConfigurationTest` NoSuchFile error with no Cortex 2.1 failures.

- [ ] **Step 3: Run web verification**

Run `npm test`, `npm run lint`, and `npm run build` in `apps/web`. Expected:
228 baseline tests plus new tests pass, lint exit 0, build exit 0.

- [ ] **Step 4: Start isolated local preview**

Use unused ports so the existing develop processes remain untouched:

```bash
cd apps/web
CORTEX_API_TARGET=http://127.0.0.1:8083 npm run dev -- --host 127.0.0.1 --port 5177
```

Run the API on 8083 only if valid local secrets/database configuration are
available. Do not claim live API proof when startup is blocked.

- [ ] **Step 5: Capture authenticated desktop/mobile evidence**

Verify Home Overview, Memory, RDO, Obras, Equipes, Mensagens, Tarefas,
Financeiro and Integrações at desktop and representative 390px mobile width.
Exercise sidebar expanded/collapsed, tab history, filters, pagination, event
expansion, Memory links, focus visibility, offline messaging, and StavIA.

- [ ] **Step 6: Audit requirements and commit verification fixes**

Compare every criterion in the design spec against current files, commands and
screenshots. Fix proven gaps using a fresh failing test when behavior changes.
If fixes are needed, stage only the exact files changed by that red/green cycle
and commit them with message `fix(web): close Cortex 2.1 verification gaps`.

---

## Execution Decision

The user approved implementation in the current conversation. Multi-agent
dispatch is disabled by repository/session instructions, so execute inline with
`superpowers:executing-plans`, retaining TDD red/green evidence and review
checkpoints in the active plan.

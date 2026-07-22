# Córtex Full Offline Tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every operational and administrative tab onto the canonical offline mutation path and automatic synchronization foundation.

**Architecture:** Each tab keeps its existing domain UI and repository where useful, but all writes delegate to `commitLocalMutation`. Missing server coverage is added through focused `SyncOperationHandler` implementations. Read paths hydrate IndexedDB online and render from local projections both online and offline; external actions become queued commands and are never simulated.

**Tech Stack:** React 19, TypeScript 6, IndexedDB/`idb`, Vite PWA, Vitest, Java 21, Spring Boot, JDBC, MySQL 8.4, Flyway.

## Global Constraints

- Requires completion of `2026-07-17-cortex-offline-ontology-foundation.md`.
- Every write uses `commitLocalMutation`; no page may separately write domain/outbox/event records.
- Every new API operation has a handler, conflict policy, authorization rule, publisher and catalog entry.
- All tabs render cached data and explicit freshness; absence is never replaced with fabricated values.
- Financeiro navigation remains revenue-only.
- External integrations queue commands as `PENDING` with `blockedReason: "AGUARDANDO_REDE"`.
- Synchronization after reconnection is automatic.
- Use JDK 21 for Maven verification.

---

### Task 1: Home projections and conflict-capable Memory

**Files:**
- Modify: `apps/web/src/features/home/useHomeData.ts`
- Modify: `apps/web/src/features/home/homeHydration.ts`
- Modify: `apps/web/src/features/home/memory/useMemoryLedger.ts`
- Modify: `apps/web/src/features/home/memory/MemoryLedger.tsx`
- Modify: `apps/web/src/features/home/memory/memoryApi.ts`
- Create: `apps/web/src/features/home/memory/memoryConflictResolution.test.ts`
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalConflictController.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalConflictControllerTest.java`

**Interfaces:**
- Produces: local-first Home projections with `dataFreshness`.
- Produces: `resolveConflict(eventId, selectedFields)` as a new queued mutation.

- [ ] **Step 1: Write failing local Memory and API conflict tests**

```ts
expect(buildMemorySections(events).reviewRequired).toEqual([
  expect.objectContaining({ result: "CONFLICT", clientMutationId: "m-1" }),
]);
```

API test: an authorized resolution creates a new mutation/event and leaves the original conflict immutable.

- [ ] **Step 2: Run RED tests**

Run web: `cd apps/web && npm test -- --run src/features/home/memory/memoryConflictResolution.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OperationalConflictControllerTest test`

Expected: FAIL because review and resolution paths are missing.

- [ ] **Step 3: Render Home from local projection first**

Load `obras`, `previsao_snapshots`, `tarefas`, `rdos` and local events immediately. Online hydration updates the stores and re-renders; network failure preserves local content and sets explicit age/status.

- [ ] **Step 4: Implement conflict resolution as a new mutation**

Use operation `RESOLVER_CONFLITO_OPERACIONAL`; payload includes original event ID, selected field values and justification. Never update the original event.

- [ ] **Step 5: Run Home and Memory suites**

Run web: `cd apps/web && npm test -- --run src/features/home/memory src/lib/sync/fieldConflict.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OperationalConflictControllerTest,OperationalMemoryQueryServiceTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/features/home apps/api/src/main/java/com/projeto/cortex/ontology apps/api/src/test/java/com/projeto/cortex/ontology/OperationalConflictControllerTest.java
git commit -m "feat(memory): review and resolve offline conflicts"
```

### Task 2: RDO and attachment adapter

**Files:**
- Modify: `apps/web/src/lib/db/localRdoService.ts`
- Modify: `apps/web/src/features/rdos/useRdoLocalPersistence.ts`
- Modify: `apps/web/src/features/rdos/RdoCreatePage.tsx`
- Modify: `apps/web/src/features/mensagens/objectUploadSync.ts`
- Modify: `apps/web/src/lib/db/localRdoService.test.ts`

**Interfaces:**
- Consumes: `commitLocalMutation` and existing RDO payload builders.
- Produces: one atomic RDO save path and dependency-aware attachment mutations.

- [ ] **Step 1: Add a regression test for one outbox/event pair per save**

Assert that saving the same draft revision does not create duplicate events, attachments depend on RDO creation, and offline reload restores the full draft.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/lib/db/localRdoService.test.ts`

Expected: FAIL while the old service constructs records/outbox/events independently.

- [ ] **Step 3: Delegate transactions to the coordinator**

Keep `buildRdoSyncPayloadFromLocalRecord` and repair functions, but replace transaction construction with `commitLocalMutation`. Use operation `CRIAR_RDO`, `ATUALIZAR_RDO_RASCUNHO` or `ENVIAR_RDO` and include child store writes in the same transaction.

- [ ] **Step 4: Keep manual sync as optional diagnostics only**

Saving emits the scheduler event. The existing “Sincronizar” control may request an immediate run but is not required for eventual completion.

- [ ] **Step 5: Run RDO, storage and upload suites**

Run: `cd apps/web && npm test -- --run src/lib/db/localRdoService.test.ts src/lib/sync/syncStorage.test.ts src/features/mensagens/objectUploadSync.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/db/localRdoService.ts apps/web/src/features/rdos/useRdoLocalPersistence.ts apps/web/src/features/rdos/RdoCreatePage.tsx apps/web/src/features/mensagens/objectUploadSync.ts apps/web/src/lib/db/localRdoService.test.ts
git commit -m "refactor(rdo): use canonical offline mutation path"
```

### Task 3: Obras, geometry and administrative links offline

**Files:**
- Create: `apps/web/src/features/obras/obraOfflineRepository.ts`
- Create: `apps/web/src/features/obras/obraOfflineRepository.test.ts`
- Modify: `apps/web/src/features/obras/ObrasPage.tsx`
- Modify: `apps/web/src/features/obras/gestao/GestaoObrasPage.tsx`
- Modify: `apps/web/src/features/obras/map/obraMapApi.ts`
- Modify: `apps/web/vite.config.ts`
- Create: `apps/api/src/main/java/com/projeto/cortex/obras/ObraSyncOperationHandler.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/obras/VinculoObraSyncOperationHandler.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/obras/ObraSyncOperationHandlerTest.java`

**Interfaces:**
- Adds operations: `CRIAR_OBRA`, `ATUALIZAR_OBRA`, `ATUALIZAR_GEOMETRIA_OBRA`, `VINCULAR_COLABORADOR_OBRA`, `REVOGAR_VINCULO_OBRA`.
- Consumes the `obra_geometries` store introduced by IndexedDB v13 in the foundation plan.

- [ ] **Step 1: Write repository and handler tests**

Cover offline create/update, local geometry reload, link dependency on pending obra creation, ALFA authorization, idempotent replay and publisher event.

- [ ] **Step 2: Run and verify RED**

Run web: `cd apps/web && npm test -- --run src/features/obras/obraOfflineRepository.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ObraSyncOperationHandlerTest test`

Expected: FAIL because repositories and handlers are missing.

- [ ] **Step 3: Add IndexedDB geometry and admin queue**

Store canonical GeoJSON and `updatedAt`; queue all mutations through the coordinator. A pending link to a pending obra lists the obra mutation in `dependsOnMutationIds`.

- [ ] **Step 4: Cache viewed map tiles without prefetching**

Add Workbox runtime rules for configured MapTiler/Mapbox tile hosts using `CacheFirst`, `maxEntries: 500`, `maxAgeSeconds: 604800`. Never claim that an unseen tile is available offline.

- [ ] **Step 5: Implement API handlers and conflict policies**

Handlers call existing `ObraService`, geometry service and link service. Declare mergeable scalar fields; geometry conflicts remain manual.

- [ ] **Step 6: Run focused suites**

Run web: `cd apps/web && npm test -- --run src/features/obras src/lib/db/localDataNamespace.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ObraSyncOperationHandlerTest,ObraServiceTest,VinculoColaboradorObraServiceTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/lib/db apps/web/src/features/obras apps/web/vite.config.ts apps/api/src/main/java/com/projeto/cortex/obras apps/api/src/test/java/com/projeto/cortex/obras/ObraSyncOperationHandlerTest.java
git commit -m "feat(obras): operate and administer worksites offline"
```

### Task 4: Equipes offline mutations

**Files:**
- Modify: `apps/web/src/features/equipes/teamLocalRepository.ts`
- Modify: `apps/web/src/features/equipes/teamLocalRepository.test.ts`
- Modify: `apps/web/src/features/equipes/EquipesPage.tsx`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeSyncOperationHandler.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeSyncOperationHandlerTest.java`

**Interfaces:**
- Adds operations for create/update/archive team, member add/update/end and worksite add/end.
- Consumes: existing `EquipeService`, local team stores and grant capabilities.

- [ ] **Step 1: Write queue and handler tests**

Assert optimistic local updates, base versions, team dependencies, BETA rejection and ALFA replay idempotency.

- [ ] **Step 2: Run RED tests**

Run web: `cd apps/web && npm test -- --run src/features/equipes/teamLocalRepository.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=EquipeSyncOperationHandlerTest test`

Expected: FAIL.

- [ ] **Step 3: Route team writes through the coordinator**

Every optimistic record carries local status and correlation ID. Existing hydration merges server versions without erasing pending local changes.

- [ ] **Step 4: Implement handler and conflict policy**

Scalar metadata may auto-merge; membership/role changes on the same participation ID require review.

- [ ] **Step 5: Run Equipes suites**

Run web: `cd apps/web && npm test -- --run src/features/equipes`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=EquipeSyncOperationHandlerTest,EquipeServiceTest,EquipeMemoryPublisherTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/features/equipes apps/api/src/main/java/com/projeto/cortex/equipes/EquipeSyncOperationHandler.java apps/api/src/test/java/com/projeto/cortex/equipes/EquipeSyncOperationHandlerTest.java
git commit -m "feat(equipes): synchronize team administration offline"
```

### Task 5: Mensagens canonical adapter

**Files:**
- Modify: `apps/web/src/features/mensagens/mensagensRepository.ts`
- Modify: `apps/web/src/features/mensagens/mensagensRepository.test.ts`
- Modify: `apps/web/src/features/mensagens/mensagensQueue.ts`
- Modify: `apps/web/src/features/mensagens/MensagensPage.tsx`

**Interfaces:**
- Consumes: existing message API handlers and object-upload dependencies.
- Produces: canonical event trace for conversation/message/attachment mutations.

- [ ] **Step 1: Add canonical trace regression tests**

Assert that conversation creation, message send and attachment upload each have one correlated event and dependency chain; reload preserves `Blob` and queue.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/features/mensagens/mensagensRepository.test.ts src/features/mensagens/mensagensQueue.test.ts`

Expected: FAIL until repository writes use the coordinator.

- [ ] **Step 3: Adapt repository writes**

Preserve existing message status translation, but source outbox/event creation from `commitLocalMutation`. New conversations are permitted offline using local participant cache and valid scope.

- [ ] **Step 4: Run all message suites**

Run: `cd apps/web && npm test -- --run src/features/mensagens`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/features/mensagens
git commit -m "refactor(mensagens): use canonical offline trace"
```

### Task 6: Tarefas server persistence and offline sync

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V44__create_operational_tasks.sql`
- Create: `apps/api/src/main/java/com/projeto/cortex/tarefas/TarefaService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/tarefas/TarefaSyncOperationHandler.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/tarefas/TarefaSyncOperationHandlerTest.java`
- Modify: `apps/web/src/lib/db/tarefaRepository.ts`
- Create: `apps/web/src/lib/db/tarefaRepository.test.ts`
- Modify: `apps/web/src/features/tarefas/TarefasPage.tsx`

**Interfaces:**
- Adds operations: `CRIAR_TAREFA`, `ATUALIZAR_TAREFA`, `CONCLUIR_TAREFA`, `REABRIR_TAREFA`, `EXCLUIR_TAREFA`.
- Persists server task versions and ontology events.

- [ ] **Step 1: Write migration, handler and repository tests**

Cover create/update/complete/reopen/delete, worksite scope, actor trace, idempotency and same-field conflict.

- [ ] **Step 2: Run and verify RED**

Run web: `cd apps/web && npm test -- --run src/lib/db/tarefaRepository.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=TarefaSyncOperationHandlerTest test`

Expected: FAIL.

- [ ] **Step 3: Create the server task aggregate**

V44 stores task UUID, worksite, assignee, priority, completion, version and timestamps. Soft-delete using `excluida_em`; never erase ontology history.

- [ ] **Step 4: Route web writes through the coordinator**

Preserve current task event labels but link them to the canonical mutation/event ID.

- [ ] **Step 5: Run Tarefas suites**

Run web: `cd apps/web && npm test -- --run src/features/tarefas src/lib/db/tarefaRepository.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=TarefaSyncOperationHandlerTest,OperationalMutationCoverageTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V44__create_operational_tasks.sql apps/api/src/main/java/com/projeto/cortex/tarefas apps/api/src/test/java/com/projeto/cortex/tarefas apps/web/src/lib/db/tarefaRepository.ts apps/web/src/lib/db/tarefaRepository.test.ts apps/web/src/features/tarefas/TarefasPage.tsx
git commit -m "feat(tarefas): persist and synchronize tasks offline"
```

### Task 7: Revenue-only Financeiro offline projection

**Files:**
- Create: `apps/web/src/features/financeiro/financeRevenueOfflineRepository.ts`
- Create: `apps/web/src/features/financeiro/financeRevenueOfflineRepository.test.ts`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceRevenueTracePage.tsx`
- Modify: `apps/web/src/features/financeiro/financeiroApi.ts`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RevenueRecalculationSyncOperationHandler.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/RevenueRecalculationSyncOperationHandlerTest.java`

**Interfaces:**
- Stores revenue trace and PDOR snapshot by obra/scope.
- Adds operation `RECALCULAR_PREVISAO_RECEITA` for queued offline requests.

- [ ] **Step 1: Write offline projection and handler tests**

Assert cached trace rendering, no fabricated revenue, queued recalculation, authorization and event publication.

- [ ] **Step 2: Run RED tests**

Run web: `cd apps/web && npm test -- --run src/features/financeiro/financeRevenueOfflineRepository.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RevenueRecalculationSyncOperationHandlerTest test`

Expected: FAIL.

- [ ] **Step 3: Hydrate and render revenue locally**

Online API responses update the local projection. Offline uses the last confirmed values with timestamp and source; missing values remain unavailable.

- [ ] **Step 4: Keep navigation revenue-only**

The page imports only `FinanceRevenueTracePage`; do not restore purchases, invoices, payments or charges sections.

- [ ] **Step 5: Run Financeiro contract tests**

Run web: `cd apps/web && npm test -- --run src/features/financeiro/financeRevenueOfflineRepository.test.ts src/features/financeiro/financeiroApi.test.ts src/features/home/institutionalUiPolicy.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RevenueRecalculationSyncOperationHandlerTest,RastreioReceitaServiceTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/features/financeiro apps/api/src/main/java/com/projeto/cortex/financeiro/RevenueRecalculationSyncOperationHandler.java apps/api/src/test/java/com/projeto/cortex/financeiro/RevenueRecalculationSyncOperationHandlerTest.java
git commit -m "feat(financeiro): keep revenue trace available offline"
```

### Task 8: Integration and role commands offline

**Files:**
- Create: `apps/web/src/features/integracoes/integrationCommandRepository.ts`
- Create: `apps/web/src/features/integracoes/integrationCommandRepository.test.ts`
- Modify: `apps/web/src/features/integracoes/IntegracoesPage.tsx`
- Modify: `apps/web/src/features/auth/DeviceSecurityPage.tsx`
- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/IntegracaoSyncOperationHandler.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/roles/AdminRoleSyncOperationHandler.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/integracoes/IntegracaoSyncOperationHandlerTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/roles/AdminRoleSyncOperationHandlerTest.java`

**Interfaces:**
- Adds operations: `TESTAR_INTEGRACAO`, `SINCRONIZAR_INTEGRACAO`, `ALTERAR_PAPEL_ACESSO`.
- External commands remain pending with `AGUARDANDO_REDE` while offline.

- [ ] **Step 1: Write command queue and authorization tests**

Assert no network call while offline, immediate local pending state, automatic eligibility after reconnection, ALFA capability enforcement and rejection audit after server revocation.

- [ ] **Step 2: Run and verify RED**

Run web: `cd apps/web && npm test -- --run src/features/integracoes/integrationCommandRepository.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=IntegracaoSyncOperationHandlerTest,AdminRoleSyncOperationHandlerTest test`

Expected: FAIL.

- [ ] **Step 3: Implement queued commands**

Integration rows show local request ID and pending reason. The handler invokes existing services only when the server processes the queue; it records provider outcome without returning fake success.

- [ ] **Step 4: Implement role command handler**

Require ALFA + `PAPEL_ADMINISTRAR`, base role version and justification. Revoked permissions return `REJEITADA` with a correlated Memory event.

- [ ] **Step 5: Run focused suites**

Run web: `cd apps/web && npm test -- --run src/features/integracoes src/features/auth/DeviceSecurityPage.policy.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=IntegracaoSyncOperationHandlerTest,AdminRoleSyncOperationHandlerTest,OperationalMutationCoverageTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/features/integracoes apps/web/src/features/auth/DeviceSecurityPage.tsx apps/api/src/main/java/com/projeto/cortex/integracoes/IntegracaoSyncOperationHandler.java apps/api/src/main/java/com/projeto/cortex/auth/roles/AdminRoleSyncOperationHandler.java apps/api/src/test/java/com/projeto/cortex/integracoes/IntegracaoSyncOperationHandlerTest.java apps/api/src/test/java/com/projeto/cortex/auth/roles/AdminRoleSyncOperationHandlerTest.java
git commit -m "feat(admin): queue integrations and role changes offline"
```

### Task 9: Cross-tab offline verification gate

**Files:**
- Create: `apps/web/src/offlineTabCoverage.test.ts`
- Modify only production files exposed by the new coverage test.

**Interfaces:**
- Produces: executable matrix proving every protected route has local reads and every mutation has an operation.

- [ ] **Step 1: Write the route/operation coverage matrix**

The test enumerates `/home`, `/rdos`, `/obras`, `/obras/gestao`, `/equipes`, `/mensagens`, `/tarefas`, `/financeiro`, `/integracoes`, `/seguranca` and asserts a local data source plus allowed offline operations or an explicit read-only reason.

- [ ] **Step 2: Run and verify coverage**

Run: `cd apps/web && npm test -- --run src/offlineTabCoverage.test.ts`

Expected: PASS only after all rows are wired.

- [ ] **Step 3: Run full web gates**

Run: `cd apps/web && npm run lint && npm test -- --run && npm run build`

Expected: exit 0 and PWA artifacts generated.

- [ ] **Step 4: Run full API gates**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test`

Expected: exit 0.

- [ ] **Step 5: Commit the coverage test**

```bash
git add apps/web/src/offlineTabCoverage.test.ts
git commit -m "test: enforce offline coverage for every Cortex tab"
```

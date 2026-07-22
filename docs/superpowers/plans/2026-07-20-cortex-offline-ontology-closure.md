# Córtex Offline and Ontology Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every supported Córtex mutation atomic, traceable, offline-capable and automatically synchronized, with complete audited results shown only in `Home > Memória`.

**Architecture:** Preserve the existing IndexedDB, scheduler, SyncService and operational-event foundations. Close the web/API canonical-envelope gap first; migrate each writer to one atomic coordinator; then make server-side field reconciliation, operation coverage and Memory query/projection exhaustive. A local record is never silently upgraded with guessed trace data: verifiable legacy records migrate, everything else remains preserved and explicitly blocked for review.

**Tech Stack:** React 19, TypeScript 6, IndexedDB/idb, Vitest, Spring Boot, Java, JDBC/MySQL, Vite PWA, browser runtime verification.

## Global Constraints

- Preserve existing working data and migrations; never reset or invent IDs, actors, scopes, versions, map data or financial values.
- Every canonical mutation contains `clientMutationId`, entity type/ID, operation, base version, `fieldPatch`, actor, device, authorization scope, creation time, dependency IDs, payload hash and ontology event ID.
- Canonical local write atomically persists the projection, outbox mutation and operational event before requesting automatic sync.
- The only canonical operational states are `LOCAL`, `PENDENTE`, `SINCRONIZANDO`, `SINCRONIZADO`, `CONFLITO`, and `REJEITADO`; legacy display labels may be adapted at the edge but may not erase rejection or conflict semantics.
- External requests made offline are persisted as `PENDENTE` with `AGUARDANDO_REDE`, then executed automatically after reconnection; they never simulate success.
- Full ontology history is exclusive to `Home > Memória`; other tabs show at most current status, trace reference and Memory link.
- Financeiro remains revenue-only.
- Server authorization, relation validation, idempotency and conflict handling are authoritative; client code cannot bypass or invent success.
- Changes in disjoint fields merge only from verified base values; same-field conflicts preserve base/local/remote values and require a new resolution mutation.
- Verification must include focused web/API tests, full relevant suites, production build/preview, online → offline → reload → reconnection, and both disjoint/same-field conflict cases.

---

### Task 1: Send and interpret the canonical mutation envelope end-to-end

**Files:**
- Modify: `apps/web/src/lib/sync/sync.types.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.ts`
- Modify: `apps/web/src/lib/sync/pushOutbox.ts`
- Modify: `apps/web/src/lib/sync/mutationContract.test.ts`
- Create: `apps/web/src/lib/sync/syncPushContract.test.ts`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncMutationTraceValidationTest.java`

**Interfaces:**
- Consumes: `CanonicalOutboxMutationRecord` and `SyncPushRequest.MutacaoCliente`.
- Produces: a JSON-equivalent `SyncPushMutationRequest` carrying `fieldPatch`, `actorId`, `authorizationScope`, `ontologyEventId`, `payloadHash`, `causationId` and `dependsOnMutationIds`; client results distinguish applied, conflict, rejected and retryable error.

- [ ] **Step 1: Write the failing web wire-contract test**

```ts
const request = toPushMutationRequest(canonicalMutation);

expect(request).toMatchObject({
  clientMutationId: canonicalMutation.clientMutationId,
  fieldPatch: canonicalMutation.fieldPatch,
  actorId: canonicalMutation.trace.actorId,
  authorizationScope: canonicalMutation.trace.authorizationScope,
  ontologyEventId: canonicalMutation.trace.ontologyEventId,
  payloadHash: canonicalMutation.trace.payloadHash,
  causationId: canonicalMutation.trace.causationId,
  dependsOnMutationIds: canonicalMutation.dependsOnMutationIds,
});
```

- [ ] **Step 2: Run the contract test and confirm RED**

Run: `cd apps/web && npm test -- --run src/lib/sync/syncPushContract.test.ts`

Expected: FAIL because `toPushMutationRequest()` currently drops canonical trace fields.

- [ ] **Step 3: Make the wire type match the server record exactly**

```ts
export interface SyncPushMutationRequest {
  clientMutationId: string;
  entidadeTipo: SyncEntityType;
  entidadeId: string;
  operacao: SyncOperation;
  baseVersao: number | null;
  payload: Record<string, unknown>;
  criadaNoClienteEm: string;
  correlacaoId: string;
  fieldPatch: MutationFieldPatch;
  actorId: string;
  authorizationScope: string[];
  ontologyEventId: string;
  payloadHash: string;
  causationId: string | null;
  dependsOnMutationIds: string[];
}
```

Reject a noncanonical record before network dispatch with an explicit local blocked reason; do not emit partial fields or silently fall back to the old contract. Extend the server trace test with the exact JSON field names and a successful accepted canonical mutation.

- [ ] **Step 4: Preserve terminal server semantics in local storage**

```ts
export type ServerMutationStatus =
  | "APLICADA"
  | "CONCILIADA"
  | "DESCARTADA"
  | "REJEITADA"
  | "ERRO";
```

Map `REJEITADA` to `REJECTED`, `DESCARTADA` to `CONFLICT`, `CONCILIADA` to synced with a reconciliation result, and only transient/unknown failures to retryable `ERROR` behavior. Update correlated operational events in the same transaction.

- [ ] **Step 5: Run focused tests and commit**

Run:

```bash
cd apps/web
npm test -- --run src/lib/sync/syncPushContract.test.ts src/lib/sync/mutationContract.test.ts src/lib/sync/pushOutbox.test.ts src/lib/sync/syncStorage.test.ts
cd ../../api
./mvnw test -Dtest=SyncMutationTraceValidationTest
```

Expected: PASS.

```bash
git add apps/web/src/lib/sync apps/api/src/test/java/com/projeto/cortex/sync/SyncMutationTraceValidationTest.java
git commit -m "fix(sync): send canonical mutation trace"
```

### Task 2: Preserve and explicitly classify legacy outbox records

**Files:**
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Modify: `apps/web/src/lib/db/outboxRepository.ts`
- Modify: `apps/web/src/lib/db/db.types.ts`
- Modify: `apps/web/src/lib/sync/mutationContract.test.ts`
- Create: `apps/web/src/lib/db/outboxCanonicalMigration.test.ts`

**Interfaces:**
- Consumes: v12/v13 stored mutations.
- Produces: canonical mutations eligible for push or preserved legacy records marked `blockedReason: "Rastro canônico ausente; requer revisão."` without guessed identifiers.

- [ ] **Step 1: Write migration tests for both evidence paths**

```ts
expect(await listReadyPendingOutboxMutations()).toEqual([]);
expect(await getOutboxMutation(legacy.clientMutationId)).toMatchObject({
  payload: legacy.payload,
  blockedReason: "Rastro canônico ausente; requer revisão.",
});
```

Also assert that a record already containing every trace field remains eligible and byte-for-byte preserves its payload and correlation IDs.

- [ ] **Step 2: Confirm RED**

Run: `cd apps/web && npm test -- --run src/lib/db/outboxCanonicalMigration.test.ts src/lib/sync/mutationContract.test.ts`

Expected: FAIL because old records can currently be selected for push without a complete trace.

- [ ] **Step 3: Add idempotent read/migration classification**

```ts
if (!isCanonicalOutboxMutation(mutation)) {
  return { ...mutation, blockedReason: LEGACY_TRACE_REVIEW_REASON };
}
```

Do not change original `payload`, `clientMutationId`, entity IDs or status to make an invalid record pass. Query helpers must exclude blocked records from automatic dispatch and expose them to `Memória > Revisão necessária`.

- [ ] **Step 4: Run web storage tests and commit**

Run: `cd apps/web && npm test -- --run src/lib/db/outboxCanonicalMigration.test.ts src/lib/db/outboxRepository.test.ts src/lib/sync/mutationContract.test.ts src/lib/sync/outboxDependencies.test.ts`

Expected: PASS.

```bash
git add apps/web/src/lib/db apps/web/src/lib/sync/mutationContract.test.ts
git commit -m "fix(sync): preserve unsafe legacy outbox records for review"
```

### Task 3: Move RDO and messaging production writers onto the atomic coordinator

**Files:**
- Modify: `apps/web/src/features/rdos/localRdoService.ts`
- Modify: `apps/web/src/features/rdos/RdoCreatePage.tsx`
- Modify: `apps/web/src/features/mensagens/mensagensRepository.ts`
- Modify: `apps/web/src/features/mensagens/mensagensQueue.ts`
- Modify: `apps/web/src/features/rdos/localRdoService.test.ts`
- Modify: `apps/web/src/features/mensagens/mensagensRepository.test.ts`
- Create: `apps/web/src/features/rdos/rdoCanonicalMutation.test.ts`
- Create: `apps/web/src/features/mensagens/mensagemCanonicalMutation.test.ts`

**Interfaces:**
- Consumes: authenticated actor/device/authorization grant and `commitLocalMutation()`.
- Produces: one transaction containing local RDO/message projection, canonical outbox record and correlated operational event; queued-event trigger after transaction completion.

- [ ] **Step 1: Write one red atomicity contract per writer**

```ts
await saveLocalRdo(input);
expect(await db.getAll("outbox_mutations")).toHaveLength(1);
expect(await db.getAll("operational_events")).toHaveLength(1);
expect(windowEvents).toContain(LOCAL_MUTATION_QUEUED_EVENT);
```

Mirror this for a message and assert each outbox record satisfies `isCanonicalOutboxMutation` with the same event/correlation IDs.

- [ ] **Step 2: Run RED**

Run: `cd apps/web && npm test -- --run src/features/rdos/rdoCanonicalMutation.test.ts src/features/mensagens/mensagemCanonicalMutation.test.ts`

Expected: FAIL because the existing writers create legacy records or separate events.

- [ ] **Step 3: Adapt writers, not product behavior**

```ts
return commitLocalMutation({
  stores: ["rdos"],
  entity: { type: "RDO", id: record.id, obraId: record.obraId },
  operation: "ATUALIZAR_RDO_RASCUNHO",
  previousState,
  newState: record.payload,
  actor,
  eventType: "RDO_EDITADO",
  write: (tx) => { void tx.objectStore("rdos").put(record); return undefined; },
});
```

Use a matching canonical event/operation for message creation/edit/delete. Preserve attachment dependency ordering; an object-upload mutation may remain an upload transport but its domain mutation still has canonical trace evidence.

- [ ] **Step 4: Run affected suites and commit**

Run: `cd apps/web && npm test -- --run src/features/rdos src/features/mensagens src/lib/sync/localMutationCoordinator.test.ts src/lib/sync/automaticSyncScheduler.test.ts`

Expected: PASS.

```bash
git add apps/web/src/features/rdos apps/web/src/features/mensagens
git commit -m "feat(offline): atomically trace RDO and messages"
```

### Task 4: Add canonical task, team, worksite and integration requests

**Files:**
- Modify: `apps/web/src/lib/db/db.types.ts`
- Modify: `apps/web/src/features/tarefas/tarefaRepository.ts`
- Modify: `apps/web/src/features/equipes/EquipesPage.tsx`
- Modify: `apps/web/src/features/obras/gestao/GestaoObrasPage.tsx`
- Modify: `apps/web/src/features/integracoes/integracoesApi.ts`
- Modify: `apps/web/src/features/integracoes/IntegracoesPage.tsx`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncOperationRegistry.java`
- Create: `apps/web/src/features/operationsCanonicalMutation.test.ts`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/CanonicalOperationsCoverageTest.java`

**Interfaces:**
- Consumes: the canonical coordinator and an explicit operation/handler per domain action.
- Produces: locally visible pending projections for task, team/vinculation, governance and external integration requests; API registry rejects orphan operation codes.

- [ ] **Step 1: Declare operation/entity codes and failing coverage test**

```ts
export type SyncEntityType = /* existing */ | "TAREFA" | "EQUIPE" | "VINCULO_OBRA" | "SOLICITACAO_INTEGRACAO";
export type SyncOperation = /* existing */ | "CRIAR_TAREFA" | "ATUALIZAR_TAREFA" | "CONCLUIR_TAREFA" | "ALTERAR_VINCULO_EQUIPE" | "SOLICITAR_INTEGRACAO";
```

The Java test must enumerate the same accepted code set from registered handlers, not an allowlist unrelated to the registry.

- [ ] **Step 2: Run RED**

Run:

```bash
cd apps/web
npm test -- --run src/features/operationsCanonicalMutation.test.ts
cd ../../api
./mvnw test -Dtest=CanonicalOperationsCoverageTest
```

Expected: FAIL because these operations have no canonical writer/handler contract.

- [ ] **Step 3: Persist each request atomically and defer external work**

```ts
await commitLocalMutation({
  entity: { type: "SOLICITACAO_INTEGRACAO", id: requestId },
  operation: "SOLICITAR_INTEGRACAO",
  transport: "SYNC_PUSH",
  /* ...canonical actor, patch and write... */
});
```

Represent unavailable network as a persisted request with `PENDENTE` and reason `AGUARDANDO_REDE`; automatic sync submits it later. Do not call a remote integration endpoint directly as the fallback.

- [ ] **Step 4: Add registered API handlers and run module tests**

Run: `cd apps/web && npm test -- --run src/features/tarefas src/features/equipes src/features/obras src/features/integracoes src/features/operationsCanonicalMutation.test.ts`

Then: `cd apps/api && ./mvnw test -Dtest=CanonicalOperationsCoverageTest,SyncOperationRegistryTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/lib/db apps/web/src/features/tarefas apps/web/src/features/equipes apps/web/src/features/obras apps/web/src/features/integracoes apps/api/src/main/java/com/projeto/cortex/sync apps/api/src/test/java/com/projeto/cortex/sync
git commit -m "feat(offline): queue canonical operational requests"
```

### Task 5: Make field reconciliation explicit and resolvable on the server

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/FieldConflictResolver.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/SyncConflictResolutionController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/SyncConflictResolutionRequest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncMutationTraceValidationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/FieldConflictResolverTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/SyncConflictResolutionTest.java`

**Interfaces:**
- Consumes: base version, `fieldPatch.baseValues`, `fieldPatch.changed` and current authorized entity state.
- Produces: `CONCILIADA` for disjoint fields, `DESCARTADA`/structured `CONFLITO` with base/local/remote values for overlapping fields, and a new correlated resolution mutation for an authorized manual resolution.

- [ ] **Step 1: Write distinct-field and same-field red tests**

```java
assertThat(resolver.resolve(base, remote, patchChangingOnly("trecho")))
        .isEqualTo(FieldResolution.merge("trecho"));
assertThat(resolver.resolve(base, remote, patchChangingOnly("status")))
        .hasConflictsFor("status");
```

The endpoint test must assert it does not rewrite the original mutation and creates a new mutation with a causation/correlation link.

- [ ] **Step 2: Confirm RED**

Run: `cd apps/api && ./mvnw test -Dtest=FieldConflictResolverTest,SyncConflictResolutionTest`

Expected: FAIL because SyncService currently rejects every base-version mismatch.

- [ ] **Step 3: Resolve with verified base values only**

```java
if (changedFieldEqualsBaseValue(fieldPatch, currentState)) {
    applyDisjointPatch();
    return reconciledResult();
}
return structuredConflict(baseValues, fieldPatch.changed(), currentState);
```

Never merge if a base value is missing, ambiguous or unauthorized. Persist a complete conflict payload and operational event; manual resolution must enter the normal mutation pipeline as a distinct request.

- [ ] **Step 4: Run sync/API suites and commit**

Run: `cd apps/api && ./mvnw test -Dtest=SyncMutationTraceValidationTest,FieldConflictResolverTest,SyncConflictResolutionTest,SyncServiceAuthorizationTest`

Expected: PASS.

```bash
git add apps/api/src/main/java/com/projeto/cortex/sync apps/api/src/test/java/com/projeto/cortex/sync
git commit -m "feat(sync): reconcile field conflicts with audit trail"
```

### Task 6: Make ontology coverage and result publication exhaustive

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMutationCatalog.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryQueryService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryFilter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryController.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMutationCoverageTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMemoryQueryServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/SyncOutcomeMemoryTest.java`

**Interfaces:**
- Consumes: registered sync operations, handlers, canonical mutation traces and final results.
- Produces: an exhaustive operation ↔ handler ↔ publisher gate and Memory rows for accepted, reconciled, rejected, conflict and discarded outcomes, with device filtering.

- [ ] **Step 1: Write red registry and outcome-query tests**

```java
assertThat(catalog.operationIds()).containsExactlyInAnyOrderElementsOf(registry.operationIds());
assertThat(memoryResults).extracting(OperationalMemoryEventResponse::result)
        .contains("SINCRONIZADO", "CONFLITO", "REJEITADO", "CONCILIADO");
```

- [ ] **Step 2: Run RED**

Run: `cd apps/api && ./mvnw test -Dtest=OperationalMutationCoverageTest,SyncOutcomeMemoryTest,OperationalMemoryQueryServiceTest`

Expected: FAIL because the catalog is partial and final sync results live only in `sync_mutacao_cliente`.

- [ ] **Step 3: Publish final outcomes into the operational-event projection**

```java
publishSyncOutcome(mutation, "REJEITADO", resultJson, errorCategory);
```

The publisher must retain event IDs, actor, device, correlation/causation, scope and entity relation. Add a `deviceId` query filter with authorization-scoped SQL; never make a client-side filter appear complete when source data is absent.

- [ ] **Step 4: Run API evidence suites and commit**

Run: `cd apps/api && ./mvnw test -Dtest=OperationalMutationCoverageTest,SyncHandlerCoverageTest,SyncOutcomeMemoryTest,OperationalMemoryQueryServiceTest`

Expected: PASS.

```bash
git add apps/api/src/main/java/com/projeto/cortex/ontology apps/api/src/main/java/com/projeto/cortex/sync apps/api/src/test/java/com/projeto/cortex/ontology
git commit -m "feat(ontology): publish every sync outcome to memory"
```

### Task 7: Complete the Home-only Memory review surface

**Files:**
- Modify: `apps/web/src/features/home/memory/memory.types.ts`
- Modify: `apps/web/src/features/home/memory/memoryApi.ts`
- Modify: `apps/web/src/features/home/memory/useMemoryLedger.ts`
- Modify: `apps/web/src/features/home/memory/MemoryLedger.tsx`
- Modify: `apps/web/src/features/home/memory/MemoryLedger.css`
- Modify: `apps/web/src/features/home/homeInstitutionalLayout.test.ts`
- Create: `apps/web/src/features/home/memory/memoryCompleteness.test.ts`

**Interfaces:**
- Consumes: device filter and final-outcome rows from Task 6, local blocked/review records and authorized conflict-resolution endpoint.
- Produces: explicit consolidated/device-only mode, device filter, all canonical results, export preserving identifiers/chains, and authorized resolution actions only in `Home > Memória`.

- [ ] **Step 1: Write red Memory completeness tests**

```ts
expect(markup).toContain("Somente este dispositivo");
expect(markup).toContain("Dispositivo");
expect(markup).toContain("Exportar registro");
expect(markup).toContain("REJEITADO");
```

Assert that a review action creates a new resolution request rather than editing an existing event.

- [ ] **Step 2: Run RED**

Run: `cd apps/web && npm test -- --run src/features/home/memory/memoryCompleteness.test.ts src/features/home/homeInstitutionalLayout.test.ts`

Expected: FAIL because the current ledger lacks explicit mode/filter/export/resolution contracts.

- [ ] **Step 3: Add only evidence-backed controls**

```ts
const exportRows = events.map((event) => ({
  id: event.id,
  correlationId: event.correlationId,
  causationId: event.causationId,
  result: event.result,
}));
```

Export the currently authorized, filtered rows. Make unavailable local/server sources explicit; do not show an empty queue until both sources are known. Keep all visual history restricted to this module.

- [ ] **Step 4: Run Memory tests and commit**

Run: `cd apps/web && npm test -- --run src/features/home src/lib/sync/syncStorage.test.ts`

Expected: PASS.

```bash
git add apps/web/src/features/home
git commit -m "feat(memory): complete audited conflict review"
```

### Task 8: Cross-tab lease, migration proof and full runtime acceptance

**Files:**
- Modify: `apps/web/src/lib/sync/syncEngine.ts`
- Create: `apps/web/src/lib/sync/deviceSyncLease.ts`
- Create: `apps/web/src/lib/sync/deviceSyncLease.test.ts`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Modify: `apps/web/src/lib/db/cortexDb.migration.test.ts`
- Create: `docs/checkpoints/cortex-offline-ontology-2026-07-20.md`

**Interfaces:**
- Consumes: browser tabs using the same isolated device store and real production preview.
- Produces: one expiring, renewable per-device sync lease; migration evidence for observed historical stores; a reproducible browser acceptance record.

- [ ] **Step 1: Write the red cross-tab lease and migration matrix tests**

```ts
expect(await acquireDeviceSyncLease("device-1", "tab-a")).toBe(true);
expect(await acquireDeviceSyncLease("device-1", "tab-b")).toBe(false);
expect(await acquireDeviceSyncLease("device-1", "tab-b", expiredNow)).toBe(true);
```

For every production schema version found in migration evidence, assert records, outbox, attachments and conflicts survive upgrade unchanged or become explicitly review-blocked.

- [ ] **Step 2: Confirm RED**

Run: `cd apps/web && npm test -- --run src/lib/sync/deviceSyncLease.test.ts src/lib/db/cortexDb.migration.test.ts`

Expected: FAIL because the current lock is in-memory per tab and migration proof covers only a synthetic v12 path.

- [ ] **Step 3: Implement lease and run all automated gates**

Use an IndexedDB metadata lease containing holder, acquisition time and expiry; release in `finally`, tolerate stale owner expiry and never overwrite a live lease. Integrate it around each engine execution.

Run:

```bash
cd apps/web
npm run lint
npm test -- --run
npm run build:local
cd ../../api
./mvnw test
```

Expected: PASS.

- [ ] **Step 4: Run production browser proof and record it**

Start the built preview and verify a prepared account through: online sync; service-worker control; network-offline reload/unlock; creation/editing in RDO, message, task, team/governance, revenue and integration request; IndexedDB outbox/events; page reload; reconnection auto-sync without pressing a manual completion button; server confirmation and Home > Memória; disjoint-field reconciliation; same-field manual review.

Record exact commands, routes, screenshots, browser console results, data IDs, test outcomes and any retained exception in `docs/checkpoints/cortex-offline-ontology-2026-07-20.md`.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/lib/sync apps/web/src/lib/db apps/web/src/lib/db/cortexDb.migration.test.ts docs/checkpoints/cortex-offline-ontology-2026-07-20.md
git commit -m "test(offline): verify canonical sync across tabs"
```

## Plan self-review

- Spec coverage: the tasks cover canonical transport, preservation of legacy data, production atomic writers, all requested tab classes, field merge/manual conflict, outcome publication, Home-only Memory completeness, cross-tab locking, migration proof and browser acceptance.
- Scope: interface redesign remains in the existing institutional UI plan; this plan changes only the real data path needed for its offline/ontology promise.
- Ambiguity resolution: a legacy mutation without verifiable trace is not sent or fabricated; it is preserved and made reviewable. A same-field conflict is not retried by changing its original base version; it creates a distinct resolution mutation.

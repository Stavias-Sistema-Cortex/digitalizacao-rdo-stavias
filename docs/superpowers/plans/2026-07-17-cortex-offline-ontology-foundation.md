# Córtex Offline and Ontology Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one atomic, idempotent and automatically synchronized mutation path that always links local data, outbox state and operational memory.

**Architecture:** Extend the existing IndexedDB/outbox implementation instead of replacing it. A web `localMutationCoordinator` writes the domain record, mutation and ontology event in one transaction; the existing sync engine gains scheduling, retry metadata and deterministic field conflict support. The API extends `SyncService` and `SyncOperationRegistry` with trace validation, field-level reconciliation and executable ontology coverage.

**Tech Stack:** React 19, TypeScript 6, `idb`, Vitest, Vite PWA/Workbox, Java 21, Spring Boot, JDBC, MySQL 8.4, Flyway.

## Global Constraints

- Preserve existing IndexedDB data and stores; upgrades must be non-destructive.
- Every code change follows red → green TDD and ends in a focused commit.
- No mutation may be applied without actor, device, authorization scope, `clientMutationId` and correlated ontology event.
- No conflict, rejection or retry may silently delete the local payload.
- Synchronization must start automatically after local writes, reconnection, app startup, foreground return and valid offline unlock.
- Backend and web ontology definitions must remain in tested parity.
- Use JDK 21 for Maven verification.
- Do not invent legacy identifiers or synthetic domain data.

---

### Task 1: Canonical web mutation and event contracts

**Files:**
- Modify: `apps/web/src/lib/db/db.types.ts`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Create: `apps/web/src/lib/sync/mutationContract.test.ts`
- Test: `apps/web/src/lib/db/localDataNamespace.test.ts`

**Interfaces:**
- Produces: `MutationTrace`, `MutationFieldPatch`, `CanonicalMutationResult`, `ObraGeometryLocalRecord`, extended `OutboxMutationRecord` and `OperationalEventRecord`.
- Consumes: existing `SyncEntityType`, `SyncOperation`, `OperationalEntityRef`.

- [ ] **Step 1: Write the failing schema/contract test**

```ts
it("opens schema v13 with trace indexes and preserves queued data", async () => {
  const db = await getCortexDb();
  expect(CORTEX_DATABASE_VERSION).toBe(13);
  expect([...db.transaction("outbox_mutations").store.indexNames]).toContain(
    "by-next-attempt-at",
  );
  expect([...db.transaction("operational_events").store.indexNames]).toEqual(
    expect.arrayContaining(["by-client-mutation-id", "by-result"]),
  );
  expect([...db.objectStoreNames]).toContain("obra_geometries");
});
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd apps/web && npm test -- --run src/lib/sync/mutationContract.test.ts`

Expected: FAIL because schema version 13 and the new indexes do not exist.

- [ ] **Step 3: Add the exact contracts**

```ts
export type CanonicalMutationResult =
  | "LOCAL"
  | "PENDING"
  | "SYNCING"
  | "SYNCED"
  | "CONFLICT"
  | "REJECTED";

export interface MutationFieldPatch {
  changed: Record<string, unknown>;
  baseValues: Record<string, unknown>;
}

export interface MutationTrace {
  actorId: string;
  deviceId: string;
  authorizationScope: string[];
  correlationId: string;
  causationId: string | null;
  ontologyEventId: string;
  payloadHash: string;
}
```

Extend `OutboxMutationRecord` with `fieldPatch`, `trace`, `nextAttemptAt`, `blockedReason`, and status `REJECTED`. Extend `OperationalEventRecord` with `clientMutationId`, `deviceId`, `correlationId`, `causationId`, `previousState`, `newState`, `result`, `errorCategory` and `entityVersion`.

Add `ObraGeometryLocalRecord` with obra ID, canonical GeoJSON, entity version, sync status and `updatedAt`. Defining the store in this single v13 upgrade prevents a second schema break during the Obras migration.

- [ ] **Step 4: Upgrade IndexedDB non-destructively**

Set `CORTEX_DATABASE_VERSION = 13`. In `upgrade`, add missing indexes only:

```ts
if (!outbox.indexNames.contains("by-next-attempt-at")) {
  outbox.createIndex("by-next-attempt-at", "nextAttemptAt");
}
if (!events.indexNames.contains("by-client-mutation-id")) {
  events.createIndex("by-client-mutation-id", "clientMutationId");
}
if (!events.indexNames.contains("by-result")) {
  events.createIndex("by-result", "result");
}
if (!database.objectStoreNames.contains("obra_geometries")) {
  const geometries = database.createObjectStore("obra_geometries", {
    keyPath: "obraId",
  });
  geometries.createIndex("by-updated-at", "updatedAt");
  geometries.createIndex("by-sync-status", "syncStatus");
}
```

- [ ] **Step 5: Run contract and existing migration tests**

Run: `cd apps/web && npm test -- --run src/lib/sync/mutationContract.test.ts src/lib/db/localDataNamespace.test.ts src/features/equipes/teamLocalRepository.test.ts src/features/mensagens/mensagensRepository.test.ts`

Expected: PASS and existing data/store assertions remain green.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/db/db.types.ts apps/web/src/lib/db/cortexDb.ts apps/web/src/lib/sync/mutationContract.test.ts
git commit -m "feat(web): define canonical offline mutation contract"
```

### Task 2: Deterministic hashing and atomic local mutation coordinator

**Files:**
- Create: `apps/web/src/lib/sync/mutationEnvelope.ts`
- Create: `apps/web/src/lib/sync/localMutationCoordinator.ts`
- Create: `apps/web/src/lib/sync/localMutationCoordinator.test.ts`
- Modify: `apps/web/src/lib/db/operationalEventRepository.ts`

**Interfaces:**
- Produces: `buildMutationEnvelope(input): Promise<OutboxMutationRecord>`.
- Produces: `commitLocalMutation<TStore>(input): Promise<{ mutation; event }>`.
- Emits: `cortex:local-mutation-queued` after `transaction.done`.

- [ ] **Step 1: Write atomicity and hash tests**

```ts
it("commits record, outbox and event atomically", async () => {
  const result = await commitLocalMutation({
    stores: ["tarefas"],
    entity: { type: "TAREFA", id: task.id, obraId: task.obraId },
    operation: "CRIAR_TAREFA",
    baseVersion: null,
    previousState: {},
    newState: task,
    actor: fixtureActor,
    write: async (tx) => tx.objectStore("tarefas").put(task),
  });
  expect(await db.get("tarefas", task.id)).toEqual(task);
  expect(await db.get("outbox_mutations", result.mutation.clientMutationId))
    .toMatchObject({ status: "PENDING" });
  expect(await db.get("operational_events", result.event.id))
    .toMatchObject({ clientMutationId: result.mutation.clientMutationId });
});

it("rolls back all three records when the domain write throws", async () => {
  await expect(commitLocalMutation(failingInput)).rejects.toThrow("boom");
  expect(await db.getAll("outbox_mutations")).toHaveLength(0);
  expect(await db.getAll("operational_events")).toHaveLength(0);
});
```

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/lib/sync/localMutationCoordinator.test.ts`

Expected: FAIL because the coordinator does not exist.

- [ ] **Step 3: Implement canonical JSON and SHA-256**

`mutationEnvelope.ts` must sort object keys recursively before hashing and reject `undefined`, non-finite numbers and functions. Return lowercase 64-character SHA-256 hex.

```ts
export async function mutationPayloadHash(value: unknown): Promise<string>;
export async function buildMutationEnvelope(
  input: BuildMutationEnvelopeInput,
): Promise<OutboxMutationRecord>;
```

- [ ] **Step 4: Implement one IndexedDB transaction**

```ts
export async function commitLocalMutation(
  input: CommitLocalMutationInput,
): Promise<CommittedLocalMutation> {
  const db = await getCortexDb();
  const tx = db.transaction(
    [...new Set([...input.stores, "outbox_mutations", "operational_events"])],
    "readwrite",
  );
  const mutation = await buildMutationEnvelope(input);
  const event = buildOperationalEvent(eventFromMutation(input, mutation));
  await input.write(tx);
  await tx.objectStore("outbox_mutations").add(mutation);
  await tx.objectStore("operational_events").add(event);
  await tx.done;
  window.dispatchEvent(new CustomEvent("cortex:local-mutation-queued"));
  return { mutation, event };
}
```

- [ ] **Step 5: Run tests**

Run: `cd apps/web && npm test -- --run src/lib/sync/localMutationCoordinator.test.ts src/lib/db/localRdoService.test.ts src/features/mensagens/mensagensRepository.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/sync/mutationEnvelope.ts apps/web/src/lib/sync/localMutationCoordinator.ts apps/web/src/lib/sync/localMutationCoordinator.test.ts apps/web/src/lib/db/operationalEventRepository.ts
git commit -m "feat(web): commit offline mutations atomically"
```

### Task 3: Automatic scheduler, backoff and single-flight execution

**Files:**
- Create: `apps/web/src/lib/sync/automaticSyncScheduler.ts`
- Create: `apps/web/src/lib/sync/automaticSyncScheduler.test.ts`
- Modify: `apps/web/src/lib/sync/useAutomaticSync.ts`
- Modify: `apps/web/src/lib/sync/syncEngine.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.ts`

**Interfaces:**
- Produces: `createAutomaticSyncScheduler(options): AutomaticSyncScheduler`.
- Consumes: `syncNow()`, online session state and `cortex:local-mutation-queued`.

- [ ] **Step 1: Write scheduler tests with fake timers**

Cover startup, local-write, `online`, visibility, 30-second interval, single-flight coalescing and exponential backoff capped at five minutes.

```ts
expect(nextRetryDelay(1)).toBeGreaterThanOrEqual(1_000);
expect(nextRetryDelay(8)).toBeLessThanOrEqual(300_000);
```

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/lib/sync/automaticSyncScheduler.test.ts`

Expected: FAIL because the scheduler module is missing.

- [ ] **Step 3: Implement the scheduler**

Use one in-flight promise and a pending-trigger flag. `request(trigger)` must return immediately when offline, but preserve queued records. A failed run updates `nextAttemptAt`; success clears retry metadata.

- [ ] **Step 4: Replace hook-owned timers with scheduler lifecycle**

`useAutomaticSync` creates the scheduler inside `useEffect`, calls `start()`, and calls `dispose()` during cleanup. Keep `hasOnlineSession()` as the online push gate; offline sessions may queue but never call authenticated endpoints.

- [ ] **Step 5: Run scheduler and existing auth tests**

Run: `cd apps/web && npm test -- --run src/lib/sync/automaticSyncScheduler.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/registerDevice.auth.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/sync/automaticSyncScheduler.ts apps/web/src/lib/sync/automaticSyncScheduler.test.ts apps/web/src/lib/sync/useAutomaticSync.ts apps/web/src/lib/sync/syncEngine.ts apps/web/src/lib/sync/syncStorage.ts
git commit -m "feat(web): synchronize queued work automatically"
```

### Task 4: Client conflict preservation and Memory review records

**Files:**
- Create: `apps/web/src/lib/sync/fieldConflict.ts`
- Create: `apps/web/src/lib/sync/fieldConflict.test.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.ts`
- Modify: `apps/web/src/features/home/memory/memory.types.ts`
- Modify: `apps/web/src/features/home/memory/memoryViewModel.ts`

**Interfaces:**
- Produces: `classifyFieldConflict(baseValues, localValues, remoteValues)`.
- Produces: `FieldConflictResolution` with `merged`, `conflicts` and `canAutoMerge`.

- [ ] **Step 1: Write merge tests**

```ts
expect(classifyFieldConflict(
  { titulo: "A", prazo: "2026-07-20" },
  { titulo: "B", prazo: "2026-07-20" },
  { titulo: "A", prazo: "2026-07-21" },
)).toEqual({
  canAutoMerge: true,
  merged: { titulo: "B", prazo: "2026-07-21" },
  conflicts: {},
});
```

Add a same-field test that preserves `base`, `local` and `remote` values.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/lib/sync/fieldConflict.test.ts`

Expected: FAIL because the classifier does not exist.

- [ ] **Step 3: Implement deterministic field comparison**

Compare canonical JSON values, not reference identity. Never merge array/object subtrees partially unless the operation catalog declares the path mergeable.

- [ ] **Step 4: Persist conflicts without deleting mutations**

`applyPushResultAtomically` must leave the outbox row with status `CONFLICT`, copy structured conflict data, and update the correlated event to result `CONFLICT`. Independent mutations remain eligible.

- [ ] **Step 5: Run storage and Memory view-model tests**

Run: `cd apps/web && npm test -- --run src/lib/sync/fieldConflict.test.ts src/lib/sync/syncStorage.test.ts src/features/home/memory/memoryViewModel.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/sync/fieldConflict.ts apps/web/src/lib/sync/fieldConflict.test.ts apps/web/src/lib/sync/syncStorage.ts apps/web/src/features/home/memory/memory.types.ts apps/web/src/features/home/memory/memoryViewModel.ts
git commit -m "feat(web): preserve field conflicts for review"
```

### Task 5: Server trace schema and idempotency validation

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V43__canonical_offline_mutation_trace.sql`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncPushRequest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/CanonicalMutationTraceMigrationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/SyncMutationTraceValidationTest.java`

**Interfaces:**
- Extends `SyncPushRequest.Mutacao` with `fieldPatch`, `actorId`, `authorizationScope`, `ontologyEventId`, `payloadHash`, `causationId` and dependency IDs.
- Reuses existing `sync_mutacao_cliente.proprietario_id`, `correlacao_id` and `payload_hash`; adds only the missing trace columns.

- [ ] **Step 1: Write migration and validation tests**

Require ASCII/binary trace identifiers, JSON field/base values, payload hash length 64 and statuses `CONFLITO`/`REJEITADA`. The migration test must build the schema through V42 first so any duplicate V29 column fails the test.

- [ ] **Step 2: Run and verify RED with JDK 21**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=CanonicalMutationTraceMigrationTest,SyncMutationTraceValidationTest test`

Expected: FAIL because V43 and request fields do not exist.

- [ ] **Step 3: Add V43 migration**

```sql
ALTER TABLE sync_mutacao_cliente
    ADD COLUMN escopo_autorizacao_json JSON NULL,
    ADD COLUMN field_patch_json JSON NULL,
    ADD COLUMN base_values_json JSON NULL,
    ADD COLUMN evento_cliente_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN causacao_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN dependencias_json JSON NULL;
```

Drop and recreate `chk_sync_mutacao_status` to accept `PENDENTE`, `APLICADA`, `ERRO`, `DESCARTADA`, `CONFLITO` and `REJEITADA`. Add an index for `(proprietario_id, evento_cliente_id)`; do not add a second actor, correlation or payload-hash column. Expand `chk_cortex_evento_resultado` to accept `CONFLITO`, `REJEITADA` and `CONCILIADA` in addition to its existing values.

- [ ] **Step 4: Validate trace before domain handler execution**

Reject malformed UUIDs, unregistered device IDs, `actorId` different from the authenticated `proprietario_id`, invalid hashes and unsupported scopes with a structured `REJEITADA` result; do not call the handler. Map request `ontologyEventId` to `evento_cliente_id`, while `evento_servidor_commit_seq` remains the authoritative reference to the event created on the server.

- [ ] **Step 5: Run focused sync tests**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=CanonicalMutationTraceMigrationTest,SyncMutationTraceValidationTest,SyncServiceSecurityTest,SyncServiceAuthorizationTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V43__canonical_offline_mutation_trace.sql apps/api/src/main/java/com/projeto/cortex/sync/SyncPushRequest.java apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java apps/api/src/test/java/com/projeto/cortex/sync/CanonicalMutationTraceMigrationTest.java apps/api/src/test/java/com/projeto/cortex/sync/SyncMutationTraceValidationTest.java
git commit -m "feat(api): persist canonical offline mutation trace"
```

### Task 6: Server field reconciliation and idempotent conflict results

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/SyncConflictPolicy.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/SyncConflictRegistry.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncPushResponse.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/SyncFieldConflictResolutionTest.java`

**Interfaces:**
- Produces: domain policies that expose current values only for declared mergeable fields.
- Returns: `APLICADA`, `CONFLITO`, `REJEITADA`, `ERRO` or previous idempotent result.

- [ ] **Step 1: Write same-field, different-field and duplicate tests**

The different-field test must apply both changes and produce `CONCILIADA_AUTOMATICAMENTE`. The same-field test must not call `handler.apply`. The duplicate test must return the stored result and preserve one event.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=SyncFieldConflictResolutionTest test`

Expected: FAIL because no conflict registry exists.

- [ ] **Step 3: Define the conflict policy**

```java
public interface SyncConflictPolicy {
    String entityType();
    Set<String> operations();
    ObjectNode currentValues(SyncMutationContext context, String entityId);
    Set<String> mergeableFields(String operation);
}
```

- [ ] **Step 4: Reconcile before applying handlers**

Compare `baseValues` to current values for each changed field. Merge only fields declared by the policy. Persist structured conflict JSON with base/local/remote triples.

- [ ] **Step 5: Run sync regression suite**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=SyncFieldConflictResolutionTest,SyncOperationRegistryTest,SyncServicePullVersionTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/sync/SyncConflictPolicy.java apps/api/src/main/java/com/projeto/cortex/sync/SyncConflictRegistry.java apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java apps/api/src/main/java/com/projeto/cortex/sync/SyncPushResponse.java apps/api/src/test/java/com/projeto/cortex/sync/SyncFieldConflictResolutionTest.java
git commit -m "feat(api): reconcile offline mutations by field"
```

### Task 7: Signed offline capabilities and rejection audit

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantClaims.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantService.java`
- Modify: `apps/web/src/features/auth/offlineVault.types.ts`
- Modify: `apps/web/src/features/auth/offlineVault.ts`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantServiceTest.java`
- Modify: `apps/web/src/features/auth/offlineVault.test.ts`

**Interfaces:**
- Produces: grant version 2 with canonical capability strings.
- Consumes: existing role and worksite scope.

- [ ] **Step 1: Write grant v2 tests**

Assert that an ALFA grant carries only server-derived capabilities, a BETA grant cannot queue admin operations, expired grants fail closed, and v1 vault metadata remains readable but requires online renewal before administrative writes.

- [ ] **Step 2: Run both test suites and verify RED**

Run web: `cd apps/web && npm test -- --run src/features/auth/offlineVault.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OfflineGrantServiceTest test`

Expected: FAIL because capabilities and version 2 are absent.

- [ ] **Step 3: Add capabilities**

Use canonical values such as `OBRA_EDITAR`, `EQUIPE_ADMINISTRAR`, `PAPEL_ADMINISTRAR`, `INTEGRACAO_EXECUTAR`, `TAREFA_EDITAR` and `RECEITA_RECALCULAR`. The server derives them; the client never promotes them.

- [ ] **Step 4: Enforce capabilities in `commitLocalMutation`**

Reject before writing when the active offline grant lacks the operation capability. Emit no domain mutation for unauthorized attempts; surface a direct UI error.

- [ ] **Step 5: Run focused tests**

Run web: `cd apps/web && npm test -- --run src/features/auth/offlineVault.test.ts src/lib/sync/localMutationCoordinator.test.ts`

Run API: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OfflineGrantServiceTest,SyncMutationTraceValidationTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantClaims.java apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantService.java apps/web/src/features/auth/offlineVault.types.ts apps/web/src/features/auth/offlineVault.ts apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantServiceTest.java apps/web/src/features/auth/offlineVault.test.ts
git commit -m "feat(auth): scope offline grants by capability"
```

### Task 8: Executable ontology coverage and UTC event integrity

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMutationCatalog.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMutationCoverageTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/memory/CortexOperationalMemoryService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/memory/CortexOperationalMemoryServiceTest.java`
- Modify: `apps/web/src/features/stavia/rdoOntologyParity.test.ts`

**Interfaces:**
- Produces: one executable definition for every operation in `SyncOperationRegistry`.
- Ensures: all event timestamps cross JDBC as `Instant`/`Timestamp` and HTTP values contain an offset.

- [ ] **Step 1: Strengthen coverage tests**

Require exact equality between sync registry operations and mutation catalog operations. Add a timestamp round-trip assertion using `2026-07-17T12:34:56Z` under `America/Sao_Paulo`.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/api && TZ=America/Sao_Paulo JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OperationalMutationCoverageTest,CortexOperationalMemoryServiceTest test`

Expected: FAIL until catalog equality and UTC persistence are implemented.

- [ ] **Step 3: Make catalog coverage exact**

Add `syncOperation` to each catalog definition. The test must reject a domain mutation with no handler, publisher, authorization rule, idempotency rule or verification test class.

- [ ] **Step 4: Use UTC-safe JDBC values**

Convert event write boundaries to `Instant` and bind through `Timestamp.from(instant)`. Preserve overloads only where callers need source compatibility; all persisted time uses UTC.

- [ ] **Step 5: Run foundation suites**

Run web: `cd apps/web && npm test -- --run src/features/stavia/rdoOntologyParity.test.ts src/lib/sync`

Run API: `cd apps/api && TZ=America/Sao_Paulo JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OperationalMutationCoverageTest,CortexOperationalMemoryServiceTest,SyncHandlerCoverageTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMutationCatalog.java apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMutationCoverageTest.java apps/api/src/main/java/com/projeto/cortex/memory/CortexOperationalMemoryService.java apps/api/src/test/java/com/projeto/cortex/memory/CortexOperationalMemoryServiceTest.java apps/web/src/features/stavia/rdoOntologyParity.test.ts
git commit -m "feat(ontology): enforce mutation coverage and UTC trace"
```

### Task 9: Foundation verification gate

**Files:**
- Modify only if a test exposes a foundation defect.

**Interfaces:**
- Produces: a green foundation before any tab migration begins.

- [ ] **Step 1: Run web quality gates**

Run: `cd apps/web && npm run lint && npm test -- --run && npm run build`

Expected: exit 0; all Vitest files pass; Vite generates the PWA service worker.

- [ ] **Step 2: Run API quality gates with JDK 21**

Run: `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test`

Expected: exit 0. If an unrelated pre-existing test is red, record exact class and evidence before continuing; do not call the foundation green.

- [ ] **Step 3: Inspect migration and diff hygiene**

Run: `git diff --check && git status --short && git log --oneline -9`

Expected: no whitespace errors and one focused commit per task.

- [ ] **Step 4: Record the foundation checkpoint**

Create `docs/checkpoints/cortex-offline-foundation-2026-07-17.md` with exact commands, pass counts, migration version and remaining tab work.

- [ ] **Step 5: Commit**

```bash
git add docs/checkpoints/cortex-offline-foundation-2026-07-17.md
git commit -m "docs: record offline foundation checkpoint"
```

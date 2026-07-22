# Cortex 3.0 Offline Ontology and Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every operational mutation locally and atomically, synchronize it automatically, and expose authorized full-history search in Home > Memória online and offline.

**Architecture:** Port the proven Cortex 2.1 mutation envelope/outbox capabilities selectively into the current App and IndexedDB schema. PostgreSQL stores the canonical commit ledger; a user-scoped local search index mirrors authorized events and retains pending/conflict evidence.

**Tech Stack:** React 19, TypeScript 6, idb 8, Vitest/fake-indexeddb, Spring JDBC, PostgreSQL `jsonb`, Flyway, Testcontainers.

## Global Constraints

- Port capabilities from `feat/cortex-2-1-memory-ui`; do not merge the branch wholesale.
- Preserve current Mensagens/App/login changes from `develop`.
- One IndexedDB transaction must store domain data, mutation, and event.
- Sync state is persisted; React state alone never proves synchronization.
- Offline search covers all cached authorized events, not one rendered page.
- Same-field conflicts retain base/local/remote values for review.
- Memory is the sole ontology history ledger in the UI.

---

### Task 1: Install the canonical mutation envelope and atomic coordinator

**Files:**
- Create from the Cortex 2.1 capability: `apps/web/src/lib/sync/mutationEnvelope.ts`
- Create: `apps/web/src/lib/sync/localMutationCoordinator.ts`
- Modify: `apps/web/src/lib/db/db.types.ts`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Modify: `apps/web/src/lib/sync/sync.types.ts`
- Create: `apps/web/src/lib/sync/mutationContract.test.ts`
- Create: `apps/web/src/lib/sync/localMutationCoordinator.test.ts`

**Interfaces:**
- Consumes: `LocalMutationCommand<TEntity>` with user, entity, worksite, base version, operation, payload, and changed fields.
- Produces: `CanonicalMutationEnvelopeV13`, correlated `OperationalEventRecord`, and domain snapshot committed atomically.

- [ ] **Step 1: Write failing mutation contract tests**

```ts
it("stores the snapshot, mutation and event in one transaction", async () => {
  await expect(commitLocalMutation(db, command)).resolves.toMatchObject({
    mutation: { schemaVersion: 13, status: "PENDING" },
    event: { syncStatus: "PENDING", clientMutationId: command.id },
  });
  expect(await readSnapshot(command.entityId)).toEqual(command.nextSnapshot);
  expect(await readOutbox(command.id)).toBeTruthy();
});

it("rolls back every store when event persistence fails", async () => {
  eventStore.failNextPut();
  await expect(commitLocalMutation(db, command)).rejects.toThrow();
  expect(await readSnapshot(command.entityId)).toBeUndefined();
  expect(await readOutbox(command.id)).toBeUndefined();
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm --prefix apps/web test -- --run src/lib/sync/mutationContract.test.ts src/lib/sync/localMutationCoordinator.test.ts`

Expected: FAIL because envelope/coordinator are missing.

- [ ] **Step 3: Implement exact envelope types**

```ts
export type CanonicalMutationEnvelopeV13 = {
  schemaVersion: 13;
  clientMutationId: string;
  deviceId: string;
  userId: string;
  obraId: string;
  entityType: string;
  entityId: string;
  operation: "CREATE" | "UPDATE" | "DELETE" | "TRANSITION";
  baseVersion: number | null;
  changedFields: string[];
  occurredAt: string;
  payload: Record<string, unknown>;
};
```

Upgrade IndexedDB without deleting old stores. Implement one `readwrite` transaction spanning the domain store, `sync_outbox`, and `operational_events`.

- [ ] **Step 4: Run tests and commit**

Run: `npm --prefix apps/web test -- --run src/lib/sync/mutationContract.test.ts src/lib/sync/localMutationCoordinator.test.ts`

Expected: PASS.

```bash
git add apps/web/src/lib/db apps/web/src/lib/sync
git commit -m "feat(sync): commit canonical offline mutations atomically"
```

### Task 2: Persist canonical mutation trace in PostgreSQL

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V46__canonical_mutation_trace.sql`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncPushRequest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/PostgresqlCanonicalMutationIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/SyncMutationTraceValidationTest.java`

**Interfaces:**
- Consumes: `CanonicalMutationEnvelopeV13` serialized in `SyncPushRequest`.
- Produces: one idempotent domain mutation + `cortex_evento_operacional` commit sequence/result in the same transaction.

- [ ] **Step 1: Write failing validation/idempotency tests**

```java
@Test
void rejectsRelatedEntityOutsideAuthorizedWorksite() {
    SyncPushRequest request = fixture().withRelatedEntity("RDO", FOREIGN_RDO_ID);
    assertThatThrownBy(() -> service.push(scopedUser(), request))
            .isInstanceOf(SyncValidationException.class)
            .hasMessageContaining("RELATED_ENTITY_SCOPE");
}

@Test
void replayReturnsOriginalCommitWithoutSecondDomainWrite() {
    SyncPushResponse first = service.push(scopedUser(), fixture());
    SyncPushResponse replay = service.push(scopedUser(), fixture());
    assertThat(replay.commitSequence()).isEqualTo(first.commitSequence());
    assertThat(domainWriteCount()).isOne();
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=SyncMutationTraceValidationTest test`

Expected: FAIL because V13 fields/entity validation are incomplete.

- [ ] **Step 3: Add V46 and transactional handling**

```sql
ALTER TABLE sync_mutacao_cliente
    ADD COLUMN IF NOT EXISTS schema_version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS changed_fields_json jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_mutation_user_client
    ON sync_mutacao_cliente (proprietario_id, client_mutation_id)
    WHERE proprietario_id IS NOT NULL AND schema_version >= 13;
```

New Cortex 3 writes set `schema_version = 13`; historical rows retain version 1. Validate schema version, UUIDs, worksite membership, principal/related entity types and ownership before the handler runs. Reuse existing `base_versao`, `evento_servidor_commit_seq`, and `erro_categoria` columns for base version, commit sequence, and safe error code. Use the original stored result for replay.

- [ ] **Step 4: Verify unit and PostgreSQL tests**

Run: `mvn -f apps/api/pom.xml -Dtest=SyncMutationTraceValidationTest test`

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlCanonicalMutationIT verify`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V46__canonical_mutation_trace.sql apps/api/src/main/java/com/projeto/cortex/sync apps/api/src/test/java/com/projeto/cortex/sync
git commit -m "feat(api): persist canonical mutation trace"
```

### Task 3: Add automatic retry and durable conflict reconciliation

**Files:**
- Create: `apps/web/src/lib/sync/automaticSyncScheduler.ts`
- Create: `apps/web/src/lib/sync/automaticSyncRetryStorage.ts`
- Create: `apps/web/src/lib/sync/fieldConflict.ts`
- Modify: `apps/web/src/lib/sync/pushOutbox.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.ts`
- Modify: `apps/web/src/lib/sync/useAutomaticSync.ts`
- Modify: `apps/web/src/App.tsx`
- Create corresponding `*.test.ts` and `App.automaticSync.test.tsx`.

**Interfaces:**
- Consumes: persisted outbox rows and browser/auth/connectivity/focus events.
- Produces: one single-flight scheduler and atomic terminal result application.

- [ ] **Step 1: Write failing scheduler/conflict tests**

```ts
it("retries a pending write after online without a manual action", async () => {
  await scheduler.start();
  network.setOnline(true);
  dispatchEvent(new Event("online"));
  await vi.runAllTimersAsync();
  expect(push).toHaveBeenCalledTimes(1);
});

it("preserves same-field conflict evidence across reload", async () => {
  await applyPushResultAtomically(conflictResult);
  await reopenDatabase();
  expect(await readConflict("m-1")).toEqual({
    field: "titulo", base: "A", local: "B", remote: "C"
  });
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm --prefix apps/web test -- --run src/lib/sync/automaticSyncScheduler.test.ts src/lib/sync/fieldConflict.test.ts`

Expected: FAIL.

- [ ] **Step 3: Implement triggers and backoff**

Use triggers `login`, `online`, `focus`, `local-write`, and retry timer. Enforce one in-flight run. Persist `attempt`, `nextAttemptAt`, `lastSafeCode`; use capped exponential backoff with jitter. Authentication/validation failures are terminal until user action; network/server transient failures retry.

- [ ] **Step 4: Apply results atomically**

`SYNCED` updates domain version and event commit metadata; `CONFLICT` stores structured fields and leaves the mutation; `REJECTED` stores a safe code; independent rows continue.

- [ ] **Step 5: Verify and commit**

Run: `npm --prefix apps/web test -- --run src/lib/sync src/App.automaticSync.test.tsx`

Expected: PASS.

```bash
git add apps/web/src/lib/sync apps/web/src/App.tsx apps/web/src/App.automaticSync.test.tsx
git commit -m "feat(web): synchronize queued work automatically"
```

### Task 4: Implement PostgreSQL full-history Memory search

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V47__operational_memory_search.sql`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryQueryService.java`
- Create: response/filter/scope records under the same package.
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMemoryQueryServiceIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMemoryControllerAuthorizationMockMvcTest.java`

**Interfaces:**
- Consumes: `q`, structural filters, UTC range, `(commitSequence,eventId)` cursor, bounded limit.
- Produces: items, next cursor, server high-water mark, authorization scope hash, and coverage metadata.

- [ ] **Step 1: Write failing search and authorization tests**

```java
mockMvc.perform(get("/api/ontology/memory")
        .param("q", "drenagem norte")
        .cookie(sessionFor(WORKSITE_A)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].obraId", everyItem(is(WORKSITE_A))))
        .andExpect(jsonPath("$.highWaterMark").isNumber());
```

The IT inserts text in event type, actor, entity name, and permitted payload text; assert ranked matching, stable cursor order, and no foreign worksite row.

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=OperationalMemoryControllerAuthorizationMockMvcTest test`

Expected: FAIL because endpoint is absent.

- [ ] **Step 3: Add indexed PostgreSQL search**

```sql
ALTER TABLE cortex_evento_operacional
    ADD COLUMN IF NOT EXISTS search_document tsvector;
CREATE INDEX IF NOT EXISTS idx_cortex_evento_search
    ON cortex_evento_operacional USING gin (search_document);
CREATE INDEX IF NOT EXISTS idx_cortex_evento_commit
    ON cortex_evento_operacional (commit_seq DESC, id DESC);
```

Populate `search_document` from whitelisted fields only. Use `websearch_to_tsquery('portuguese', :q)` with a trigram fallback for IDs/names. Never scan arbitrary payload JSON.

- [ ] **Step 4: Verify PostgreSQL and authorization tests**

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=OperationalMemoryQueryServiceIT verify`

Run: `mvn -f apps/api/pom.xml -Dtest=OperationalMemoryControllerAuthorizationMockMvcTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V47__operational_memory_search.sql apps/api/src/main/java/com/projeto/cortex/ontology apps/api/src/test/java/com/projeto/cortex/ontology
git commit -m "feat(ontology): expose scoped operational memory search"
```

### Task 5: Build the Home > Memória offline ledger

**Files:**
- Create from `feat/cortex-2-1-memory-ui`: `apps/web/src/features/home/HomeSubnav.tsx`
- Create from `feat/cortex-2-1-memory-ui`: `apps/web/src/features/home/HomeOverview.tsx`
- Create: `apps/web/src/features/home/memory/MemoryLedger.tsx`
- Create: `apps/web/src/features/home/memory/useMemoryLedger.ts`
- Create: `apps/web/src/features/home/memory/memoryApi.ts`
- Create: `apps/web/src/features/home/memory/memoryRepository.ts`
- Create: `apps/web/src/features/home/memory/memorySearchDocument.ts`
- Modify: `apps/web/src/features/home/HomePage.tsx`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Create corresponding Vitest files.

**Interfaces:**
- Consumes: server Memory page, local pending events, user-scoped cache/high-water marks.
- Produces: `Visão geral`/`Memória` tabs, full local search, structural filters, conflict links, truthful coverage status.

- [ ] **Step 1: Write failing view-model and offline search tests**

```ts
it("searches cached history beyond the rendered page", async () => {
  await repository.putPage(pageWith(150));
  const visible = await repository.search({ q: "compactacao", limit: 20 });
  expect(visible.map((item) => item.id)).toContain("event-137");
});

it("labels incomplete offline coverage as partial", () => {
  expect(memoryCoverage({ online: false, cachedFrom: 30, serverHighWater: 90 }))
    .toEqual({ code: "PARTIAL", label: "Parcial" });
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm --prefix apps/web test -- --run src/features/home/memory`

Expected: FAIL because memory feature/repository are absent.

- [ ] **Step 3: Implement user-scoped cache and normalized search**

Store `MemorySearchDocument { userId, eventId, commitSequence, normalizedText, structuralKeys, syncStatus }`. Normalize case/diacritics once on write. Query all cached documents by user/scope and then apply bounded result sorting; do not limit to the current React page.

- [ ] **Step 4: Implement ledger UI**

Keep Memória as the only ontology history. Render literal `Atualizado`, `Parcial`, `Local pendente`, `Sincronizando`, `Conflito`, or `Rejeitado`; show the evidence source and commit/event IDs. Empty results describe active filters and never create sample events.

- [ ] **Step 5: Verify and commit**

Run: `npm --prefix apps/web test -- --run src/features/home src/lib/db`

Run: `npm --prefix apps/web run build`

Expected: PASS.

```bash
git add apps/web/src/features/home apps/web/src/lib/db
git commit -m "feat(web): add offline operational Memory search"
```

### Task 6: Prove reconnect and graph freshness end to end

**Files:**
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/PostgresqlOfflineGraphFlowIT.java`
- Create: `apps/web/src/features/home/memory/memoryReconnect.test.ts`
- Create: `docs/verification/cortex-3/offline-memory-evidence.md`

**Interfaces:**
- Consumes: Tasks 1–5 and runtime foundation.
- Produces: reproducible evidence for offline write → automatic push → canonical event → graph projection → Memory query.

- [ ] **Step 1: Write the failing integrated scenario**

Create one worksite-scoped fixture, a client mutation ID, and assertions that commit sequence, graph checkpoint, and Memory result converge exactly once after replay.

- [ ] **Step 2: Run and verify RED before wiring missing edges**

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlOfflineGraphFlowIT verify`

Expected: FAIL at the first unwired integration boundary.

- [ ] **Step 3: Wire canonical commit publication to graph projection**

Call `GraphProjectionService.projectCommitted(commitSequence)` only after the domain transaction commits; make repeated delivery idempotent. Surface checkpoint lag in the Memory response coverage metadata.

- [ ] **Step 4: Verify all slice tests**

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test='PostgresqlCanonicalMutationIT,OperationalMemoryQueryServiceIT,PostgresqlOfflineGraphFlowIT' verify`

Run: `npm --prefix apps/web test -- --run src/lib/sync src/features/home/memory`

Expected: PASS.

- [ ] **Step 5: Record exact command output and commit**

```bash
git add apps/api/src/test apps/web/src/features/home/memory docs/verification/cortex-3/offline-memory-evidence.md
git commit -m "test(cortex): prove offline ontology synchronization"
```

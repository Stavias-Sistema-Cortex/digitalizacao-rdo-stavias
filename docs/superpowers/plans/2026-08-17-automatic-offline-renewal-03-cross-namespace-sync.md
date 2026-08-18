# Córtex Automatic Offline Renewal — Cross-Namespace Sync Reconciler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make work left in an older operational IndexedDB namespace reachable and replayable after a scope change, without copying data into the active namespace, replacing the originating device identity, weakening online authorization, or confusing transport synchronization with financial acceptance.

**Architecture:** This is plan **3 of 3** for the approved automatic offline renewal design. A small central IndexedDB records every operational namespace discovered or created for an authenticated owner and owns the only fallback lease for synchronization. The normal current-namespace sync runs first under one global per-owner lock; then a bounded reconciler opens at most one older database by an explicit typed handle, preserves the origin device ID, and executes object upload, RDO photo upload/link, and generic push in that order. Pull and cursor acknowledgement remain current-namespace-only. Every result is persisted in the database where the work originated, and the sync UI reads safe aggregate diagnostics from the central registry.

**Tech Stack:** React 19, TypeScript 6, Vite 8, Vitest 4, idb 8, fake-indexeddb, IndexedDB, Web Locks, Fetch, existing Córtex sync HTTP APIs, GitHub Actions, Render, and Cloudflare Pages.

**Spec:** docs/superpowers/specs/2026-08-17-automatic-offline-grant-renewal-design.md

## Dependencies and Rollout Order

- This plan depends on docs/superpowers/plans/2026-08-17-automatic-offline-renewal-01-auth-epoch-session.md and docs/superpowers/plans/2026-08-17-automatic-offline-renewal-02-device-capsule-pwa.md.
- Plan 1 produces an epoch-bound online session and opaque session marker. Plan 2 parses that value as `OnlineAuthProfile.sessionMarker`; offline profiles cannot carry it. This plan consumes it only through `SyncSessionGuard` and `syncSessionFingerprint`; a new login by the same owner must invalidate a captured reconciliation run.
- This plan does not import capsule types, ownerLookup, sessionLookup, device capsule cryptography, or withGrantCapsuleLock. It creates its own CortexDatabase, namespace registry, and per-owner synchronization lease contracts.
- Plan 2 never opens an operational database. This plan never opens or writes cortex-auth-vaults, grant_capsules, grant_capsule_keys, or grant_capsule_state.
- Implement and commit plans 1 and 2 on the same unreleased feature branch before enabling this reconciler. Do not merge either plan to `develop` separately. Publish all three plans in one exact revision only after their focused and combined tests pass. The scope-change browser journey is a rollout acceptance gate after plans 1 and 2, not a reason to deploy plan 3 independently.

## Global Constraints

- Preserve all three transport classes: generic outbox rows whose transport is SYNC_PUSH or absent, OBJECT_UPLOAD rows plus mensagem_anexos, and pending binary photos in rdo_attachments even when no outbox row exists.
- Never call getCortexDb() while processing an old namespace. Every old-database repository and processor receives a CortexDatabase handle explicitly.
- Never copy, rename, re-key, merge, or rewrite an old mutation into the active namespace. Preserve database name, clientMutationId, payload, ordering fields, dependency IDs, correlation/causation IDs, hashes, attachment IDs, and RDO photo IDs.
- Read sync_state.deviceId from the origin database. A generic push uses that exact ID as SyncPushRequest.dispositivoId. A canonical v13 row must also have mutation.deviceId and mutation.trace.deviceId equal to the origin ID. Missing or divergent values fail closed before any row becomes SYNCING.
- Verify sync_state.usuarioId equals the authenticated guard userId. A namespace registered under another owner, or a malformed database name, is never opened for replay.
- Re-register the exact origin device ID with the online server when generic push is eligible. Never generate a new device ID for an old namespace and never write the active namespace device ID into old state.
- Coordinate the active scheduler and old-namespace reconciler with one global per-owner sync lock. Prefer Web Locks; the fallback lease lives in the central registry, not sync_state in an operational database. This lock is distinct from the capsule-renewal lock.
- Run the active namespace first. Reconcile at most one previous namespace per cycle and cap each transport by its existing batch limit so active RDO work is not starved.
- Preserve transport order inside the old namespace: OBJECT_UPLOAD, RDO photo upload/link, then generic SYNC_PUSH. Resolve durable message-upload references before selecting generic push rows.
- SYNC_PUSH is successful only after a server result APLICADA has been applied durably in the origin database. Targeted pruning may then remove only those applied rows that satisfy the existing citation and receipt-retention rules.
- OBJECT_UPLOAD is successful only after server upload integrity is confirmed and the origin outbox row plus mensagem_anexos state are durably updated. It is never sent to the generic push endpoint.
- An RDO photo is successful only after object upload, server-side RDO attachment linking, and a durable origin rdo_attachments row with syncStatus SYNCED. A photo-only namespace must be discoverable without an outbox row.
- Pull, hydration from pull, and /sync/ack are exclusive to the active namespace. No old cursor advances and no old work is inferred complete from an acknowledgement.
- Network failure, server rejection, conflict, local transaction failure, origin mismatch, and interrupted work remain in the origin database with safe diagnostic state. Do not automatically delete an old namespace in this release.
- A lost lease, logout, owner switch, scope/session marker change, or 401-driven session invalidation aborts reconciliation immediately. No later transaction may write through a stale guard.
- Every sync-engine or previous-namespace transport API in this plan that can persist a network result receives both the captured `SyncSessionGuard` and the owner `SyncExecutionLease`. It calls `await lease.assertOwned()` and `assertSyncSession(guard)` immediately before opening each local readwrite transaction, with no intervening await; the transaction is also wrapped by the lease-aware `guardSyncTransaction(transaction, guard, lease)`, which aborts on either session replacement or `lease.signal`. This applies equally to success, retry/failure, attachment puts, result application, pruning, and registry diagnostics inside active sync/reconciliation. General page-initiated online refresh/cache flows are outside this plan and retain their current contracts; this plan makes no broader claim about them.
- Before every mutating HTTP request, revalidate the same owner lease and captured session after all body/hash preparation and immediately before fetch. A stale run must make zero `/sync/push`, `/sync/ack`, device-registration, `/objetos`, or RDO-link mutation calls after lease/session loss; server idempotency remains defense in depth, not the concurrency boundary.
- Terminal review state must be visible in the sync UI without exposing payloads, CPF, raw owner IDs, database fingerprints, session markers, or attachment contents. The UI must not offer an old-namespace destructive reset.
- Transport synchronization is not financial acceptance. Existing server authorization, idempotency, immutable evidence, RDO acceptance, revenue decision, and PDOR recalculation remain authoritative.
- Unit tests, IndexedDB integration tests, full frontend checks, backend/PostgreSQL checks from plan 1, CI, exact-revision deployment, and authenticated browser acceptance are separate evidence layers.

## File Map

### New files

- apps/web/src/lib/db/cortexNamespaceRegistry.ts — central namespace catalog, discovery marker, safe aggregate counts, diagnostics, and fallback owner leases.
- apps/web/src/lib/db/cortexNamespaceRegistry.test.ts — strict-name parsing, discovery backfill, owner isolation, count refresh, and registry lease tests.
- apps/web/src/lib/sync/originDevice.ts — validates and re-registers the immutable device identity of an origin database.
- apps/web/src/lib/sync/originDevice.test.ts — missing/mismatched owner and device fail-closed tests.
- apps/web/src/lib/sync/syncSession.test.ts — session-marker generation and stale-guard tests.
- apps/web/src/lib/sync/crossNamespaceReconciler.ts — bounded three-transport orchestration over explicit old database handles.
- apps/web/src/lib/sync/crossNamespaceReconciler.test.ts — generic push, order, origin ID, no pull/ack, lock/session, pruning, and two-cycle tests.
- apps/web/src/lib/sync/crossNamespaceReconciler.objectUpload.test.ts — object-upload-only namespace proof.
- apps/web/src/lib/sync/crossNamespaceReconciler.rdoPhoto.test.ts — RDO-photo-only namespace proof.
- apps/web/src/lib/sync/crossNamespaceStatus.ts — safe old-namespace aggregate projection for the UI.
- apps/web/src/lib/sync/crossNamespaceStatus.test.ts — privacy, counts, review, and error projection tests.
- apps/web/src/components/SyncStatusBanner.previousNamespaces.test.tsx — accessible previous-scope status rendering and no-destructive-action proof.

### Existing files modified

- apps/web/src/lib/db/localDataNamespace.ts
- apps/web/src/lib/db/localDataNamespace.test.ts
- apps/web/src/lib/db/cortexDb.ts
- apps/web/src/lib/db/outboxRepository.ts
- apps/web/src/lib/db/rdoAttachmentRepository.ts
- apps/web/src/lib/db/syncStateRepository.ts
- apps/web/src/lib/db/localRdoService.ts
- apps/web/src/bootstrap/normalBootstrap.tsx
- apps/web/src/bootstrap/normalBootstrap.pwaUpdate.test.tsx
- apps/web/src/App.tsx
- apps/web/src/App.authNotice.test.tsx
- apps/web/src/App.offlineUnlock.test.tsx
- apps/web/src/App.onlineHandoff.test.tsx
- apps/web/src/lib/sync/syncSession.ts
- apps/web/src/lib/sync/syncSession.test.ts
- apps/web/src/lib/sync/syncExecutionLease.ts
- apps/web/src/lib/sync/syncExecutionLease.test.ts
- apps/web/src/lib/sync/registerDevice.ts
- apps/web/src/lib/sync/pullEvents.ts
- apps/web/src/lib/sync/ackCursor.ts
- apps/web/src/lib/sync/pushOutbox.ts
- apps/web/src/lib/sync/pushOutbox.test.ts
- apps/web/src/lib/sync/syncStorage.ts
- apps/web/src/lib/sync/syncStorage.test.ts
- apps/web/src/features/mensagens/objectUploadSync.ts
- apps/web/src/features/mensagens/objectUploadSync.test.ts
- apps/web/src/features/mensagens/objectUploadSync.session.test.ts
- apps/web/src/features/mensagens/mensagensHydration.ts
- apps/web/src/features/mensagens/mensagensHydration.session.test.ts
- apps/web/src/features/mensagens/mensagensRepository.ts
- apps/web/src/features/rdos/rdoPhotoSync.ts
- apps/web/src/features/rdos/rdoPhotoSync.test.ts
- apps/web/src/lib/sync/syncEngine.ts
- apps/web/src/lib/sync/syncEngine.session.test.ts
- apps/web/src/lib/sync/sync.types.ts
- apps/web/src/lib/sync/automaticSyncScheduler.ts
- apps/web/src/lib/sync/automaticSyncScheduler.test.ts
- apps/web/src/lib/sync/useAutomaticSync.ts
- apps/web/src/lib/sync/janelaCheiaPedeOutra.test.ts
- apps/web/src/lib/sync/useSyncStatus.ts
- apps/web/src/lib/sync/useSyncStatus.test.ts
- apps/web/src/components/SyncStatusBanner.tsx
- apps/web/src/components/SyncStatusBanner.css

---

### Task 1: Add Strict Explicit Database Handles and the Central Namespace Registry

**Files:**
- Modify: apps/web/src/lib/db/localDataNamespace.ts:3-30
- Modify: apps/web/src/lib/db/localDataNamespace.test.ts
- Modify: apps/web/src/lib/db/cortexDb.ts:357-375, 1042-1090
- Create: apps/web/src/lib/db/cortexNamespaceRegistry.ts
- Create: apps/web/src/lib/db/cortexNamespaceRegistry.test.ts
- Modify: apps/web/src/bootstrap/normalBootstrap.tsx:9-35
- Modify: apps/web/src/bootstrap/normalBootstrap.pwaUpdate.test.tsx
- Modify: apps/web/src/App.tsx:206-221
- Modify: apps/web/src/App.authNotice.test.tsx
- Modify: apps/web/src/App.offlineUnlock.test.tsx
- Modify: apps/web/src/App.onlineHandoff.test.tsx

**Interfaces:**
- Consumes: currentDataDatabaseName(), requireDataScope().ownerId, CORTEX_DATABASE_VERSION, and browser indexedDB.databases().
- Produces:

~~~ts
export type CortexDatabase = IDBPDatabase<CortexDbSchema>;

export type ParsedDataDatabaseName = {
  ownerId: string;
  scopeFingerprint: string;
};

export function parseDataDatabaseName(
  databaseName: string,
): ParsedDataDatabaseName | null;

export function openCortexDbByName(
  databaseName: string,
): Promise<CortexDatabase>;

export function openExistingCortexDbByName(
  databaseName: string,
): Promise<CortexDatabase>;

export function closeCortexDbByName(
  databaseName: string,
): Promise<void>;

export type NamespaceTransportCounts = {
  syncPush: number;
  objectUpload: number;
  rdoPhoto: number;
};

export type DataNamespaceRecord = {
  databaseName: string;
  ownerId: string;
  scanState: "UNKNOWN" | "SCANNED" | "ERROR" | "TERMINAL";
  pending: NamespaceTransportCounts;
  eligible: NamespaceTransportCounts;
  review: number;
  firstSeenAt: string;
  lastSeenAt: string;
  lastReconciledAt: string | null;
  retryAttempt: number;
  nextAttemptAt: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
};

export function initializeCurrentCortexNamespace(): Promise<void>;
export function backfillLegacyDataNamespaces(): Promise<void>;
export function listOwnerNamespaces(
  ownerId: string,
): Promise<DataNamespaceRecord[]>;
export function markDataNamespaceActive(
  input: Pick<DataNamespaceRecord, "databaseName" | "ownerId">,
): Promise<void>;
export type NamespaceStatusSnapshot = Pick<
  DataNamespaceRecord,
  "pending" | "eligible" | "review"
> & {
  earliestTransportAttemptAt: string | null;
};
export function inspectNamespaceStatus(
  database: CortexDatabase,
): Promise<NamespaceStatusSnapshot>;
~~~

- [ ] **Step 1: Write strict-name, explicit-handle, and discovery tests in RED**

In localDataNamespace.test.ts, prove that only a UUID owner plus one exact 64-hex fingerprint parses:

~~~ts
const ownerId = "00000000-0000-4000-8000-000000000031";
const fingerprint = "a".repeat(64);

expect(parseDataDatabaseName(
  "cortex-data-v1-" + ownerId + "-" + fingerprint,
)).toEqual({ ownerId, scopeFingerprint: fingerprint });
expect(parseDataDatabaseName(
  "cortex-data-v1-" + ownerId + "-" + fingerprint + "-suffix",
)).toBeNull();
expect(parseDataDatabaseName("cortex-auth-vaults")).toBeNull();
~~~

In cortexNamespaceRegistry.test.ts, create two valid fake-indexeddb databases for the same owner, one valid database for another owner, and malformed lookalikes. Backfill must register only strict operational names, must not create a name returned only by malformed input, and owner queries must be isolated:

~~~ts
await backfillLegacyDataNamespaces();

expect(
  (await listOwnerNamespaces(ownerId)).map((row) => row.databaseName),
).toEqual([currentName, previousName].sort());
expect(await listOwnerNamespaces(otherOwnerId)).toHaveLength(1);
expect(await registryRecord("cortex-auth-vaults")).toBeUndefined();
~~~

After that scan records COMPLETE, create another valid namespace as if an older
bundle still open in another tab changed scope without registry support. The
next authenticated startup/backfill must scan again and register it. COMPLETE
describes the last scan result; it is never a permanent skip bit.

Also cover coexistence on an already-known namespace: reconcile A as
SCANNED/zero, let an old bundle enqueue work in A without calling
`markDataNamespaceActive`, then run the next authenticated discovery. Discovery
must invalidate nonterminal A back to UNKNOWN; the bounded reconciler later
opens it and finds the new row. A TERMINAL owner/device record is not revived by
discovery alone.

Update the bootstrap and App harnesses before changing either import. Mock
`initializeCurrentCortexNamespace` instead of `initializeCortexDb`, then prove
that normal bootstrap and the authenticated session-scope effect await the new
wrapper exactly once. Preserve the existing offline-unlock and auth-notice
assertions; this registry change must not make them depend on a real IndexedDB.

Delete one discovered database after registration and prove openExistingCortexDbByName rejects with NAMESPACE_NOT_FOUND without recreating it. Add a status test with one PENDING SYNC_PUSH row, one PENDING OBJECT_UPLOAD row with mensagem_anexos, one pending rdo_attachments photo and one REJECTED row. It must return pending counts 1/1/1, eligible counts 1/1/1, and review count 1 without serializing a mutation payload.

Add the second-visit regression: register namespace A, seed its registry row as
SCANNED with zero counts, activate B, then call `markDataNamespaceActive(A)` and enqueue new
offline work in A before activating B again. A must be UNKNOWN regardless of its
old zero counts and must be returned as a reconciliation candidate. Repeated
activation updates lastSeenAt but never loses owner isolation.

- [ ] **Step 2: Run the focused tests and record RED**

~~~bash
cd apps/web
npm test -- src/lib/db/localDataNamespace.test.ts src/lib/db/cortexNamespaceRegistry.test.ts
~~~

Expected: FAIL because parseDataDatabaseName, explicit handles, the registry database, and discovery do not exist.

- [ ] **Step 3: Split current lookup from explicit database opening**

Add the exact parser in localDataNamespace.ts:

~~~ts
const DATA_DATABASE_NAME_PATTERN =
  /^cortex-data-v1-([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})-([0-9a-f]{64})$/;

export function parseDataDatabaseName(
  databaseName: string,
): ParsedDataDatabaseName | null {
  const match = DATA_DATABASE_NAME_PATTERN.exec(databaseName);
  return match
    ? { ownerId: match[1], scopeFingerprint: match[2] }
    : null;
}
~~~

Refactor cortexDb.ts so the existing cache is keyed by the supplied database name. openCortexDbByName validates the strict parser before calling openDB; getCortexDb remains a compatibility wrapper:

~~~ts
export type CortexDatabase = IDBPDatabase<CortexDbSchema>;

export async function openCortexDbByName(
  databaseName: string,
): Promise<CortexDatabase> {
  if (!parseDataDatabaseName(databaseName)) {
    throw new Error("Namespace operacional inválido.");
  }
  return openCachedCortexDatabase(databaseName);
}

export async function getCortexDb(): Promise<CortexDatabase> {
  return openCortexDbByName(await currentDataDatabaseName());
}
~~~

openExistingCortexDbByName is the only opener allowed to the reconciler. It first proves that the strict name already exists with indexedDB.databases() when available and also aborts an initial open whose upgrade oldVersion is 0; a stale registry row must fail with NAMESPACE_NOT_FOUND and must not recreate an empty database. closeCortexDbByName closes and removes exactly one cached connection. Preserve closeCortexDb and the AUTH_SESSION_CHANGED_EVENT handler so logout still closes every operational handle.

- [ ] **Step 4: Implement the central registry and lazy discovery backfill**

Use a separate fixed database named cortex-data-namespace-registry-v1 with stores namespaces, owner_sync_leases, and metadata. namespaces has a by-owner index; owner_sync_leases is keyed by ownerId; metadata stores the most recent legacy-backfill-v1 diagnostic, not a one-time migration sentinel.

Backfill calls indexedDB.databases() on every authenticated startup, including
after a previous COMPLETE result, filters every returned name through
parseDataDatabaseName, and registers names as scanState UNKNOWN without opening
them. Repeated discovery is idempotent and catches a namespace created later by
an older still-running bundle during rollout. This keeps startup bounded; the
reconciler opens and counts at most one unknown old database per cycle. If
databases() is unavailable or rejects, register the current namespace, persist
a safe REGISTRY_DISCOVERY_UNAVAILABLE diagnostic, and retry on a later eligible
startup.

~~~ts
type RegistryMetadataRecord = {
  key: "legacy-backfill-v1";
  status: "COMPLETE" | "FAILED" | "UNSUPPORTED";
  updatedAt: string;
  safeError: string | null;
};

export async function registerDataNamespace(
  input: Pick<DataNamespaceRecord, "databaseName" | "ownerId">,
): Promise<void> {
  const parsed = parseDataDatabaseName(input.databaseName);
  if (!parsed || parsed.ownerId !== input.ownerId) {
    throw new Error("Namespace e dono não correspondem.");
  }
  // Authenticated discovery refreshes every nonterminal discovered row to UNKNOWN.
}
~~~

`registerDataNamespace` is the idempotent authenticated-discovery path. On each
startup scan it validates the strict name/owner pair, preserves firstSeenAt and
safe diagnostics, updates lastSeenAt, and changes every nonterminal discovered
row to UNKNOWN without opening it. This deliberately rescans old namespaces
during mixed-bundle rollout and catches writes made after an earlier zero
snapshot; the reconciler still opens at most one per cycle. TERMINAL rows stay
terminal until the namespace becomes the validated active scope or an explicit
operator action clears the condition. `markDataNamespaceActive` is a distinct
lifecycle operation: it validates the same strict name/owner pair and atomically
updates lastSeenAt, sets scanState to UNKNOWN, clears stale aggregate counts and
retry diagnostics, and preserves firstSeenAt. This explicit invalidation means
a namespace that becomes current again can later be rediscovered even when its
previous registry snapshot was SCANNED with zero work.

`inspectNamespaceStatus` is read-only. It reads only outbox_mutations,
mensagem_anexos, rdo_attachments, and the RDO rows referenced by pending photos
from the supplied handle and returns aggregate counts without touching the
central registry. pending counts every unsent PENDING/SYNCING row plus
retry-scheduled ERROR, separated into SYNC_PUSH and OBJECT_UPLOAD, and every
local photo with no storedObjectId, no removedAt, and status other than
SYNC_FAILED, whether or not its Blob is still present. eligible counts only rows
that can run or be terminally classified now:
selectReadyOutboxMutations for generic push, due PENDING object uploads, and
photos whose local RDO already has a server version; a missing Blob remains
eligible so Task 6 can persist a guarded SYNC_FAILED review result without a
network request. A server-seeded attachment with storedObjectId and no Blob is
not local pending work. review counts CONFLICT,
REJECTED, nonretryable ERROR, failed message attachments, and SYNC_FAILED
photos. It also counts generic rows reported by
`explicarPendenciasQueNaoSobem` as blocked by a cycle/missing dependency and
local photos that have a Blob but neither a server-versioned RDO nor an eligible
local RDO-create mutation. Those rows remain preserved for explicit review
instead of becoming invisible SCANNED/pending work; a later authenticated
discovery or namespace activation can rescan them if another bundle adds the
missing prerequisite. Add generic-cycle-only and orphan-photo-only tests that
show nonzero review, zero request, and no automatic tight loop. Task 7 owns the only sync-result writers that persist these aggregates,
retry state, or safe diagnostics under the captured session guard and owner
lease. The snapshot also carries the minimum valid future `nextAttemptAt` among
pending SYNC_PUSH/OBJECT_UPLOAD rows. Persisting that timestamp does not
increment registry retryAttempt; it makes a row-level retry discoverable after
the namespace closes. Photo backoff remains registry-owned because photos do
not carry an outbox schedule.

- [ ] **Step 5: Register the active namespace at both initialization boundaries**

initializeCurrentCortexNamespace must await initializeCortexDb(), call `markDataNamespaceActive` with the current name plus requireDataScope().ownerId, and then run discovery backfill. Replace direct initializeCortexDb calls in normalBootstrap.tsx and the App session-scope effect with this function. The active mark occurs on every real namespace activation, not only the first time that database is created.

The plan 2 AuthProfile.sessionMarker will already participate in sessionScope. Do not import capsule state here. When the same owner logs in again, App initializes the current namespace for the new session generation and the existing AUTH_SESSION_CHANGED_EVENT closes handles captured by the prior generation.

- [ ] **Step 6: Run focused and initialization regressions in GREEN**

~~~bash
cd apps/web
npm test -- src/lib/db/localDataNamespace.test.ts src/lib/db/cortexNamespaceRegistry.test.ts src/lib/db/cortexDbAssistantCleanup.test.ts src/features/auth/authSession.test.ts
npm test -- src/bootstrap/normalBootstrap.pwaUpdate.test.tsx src/App.authNotice.test.tsx src/App.offlineUnlock.test.tsx src/App.onlineHandoff.test.tsx
~~~

Expected: PASS, including malformed-name rejection, same-owner discovery, other-owner isolation, unknown lazy scan state, second-visit invalidation of an old SCANNED/zero row, and active database initialization.

- [ ] **Step 7: Commit**

~~~bash
git add apps/web/src/lib/db/localDataNamespace.ts apps/web/src/lib/db/localDataNamespace.test.ts apps/web/src/lib/db/cortexDb.ts apps/web/src/lib/db/cortexNamespaceRegistry.ts apps/web/src/lib/db/cortexNamespaceRegistry.test.ts apps/web/src/bootstrap/normalBootstrap.tsx apps/web/src/bootstrap/normalBootstrap.pwaUpdate.test.tsx apps/web/src/App.tsx apps/web/src/App.authNotice.test.tsx apps/web/src/App.offlineUnlock.test.tsx apps/web/src/App.onlineHandoff.test.tsx
git commit -m "feat(sync): register operational namespaces"
~~~

---

### Task 2: Move Synchronization Exclusion to One Global Per-Owner Lock

**Files:**
- Modify: apps/web/src/lib/db/cortexNamespaceRegistry.ts
- Modify: apps/web/src/lib/db/cortexNamespaceRegistry.test.ts
- Modify: apps/web/src/lib/sync/syncSession.ts:8-42
- Create: apps/web/src/lib/sync/syncSession.test.ts
- Modify: apps/web/src/lib/sync/syncExecutionLease.ts:13-266
- Modify: apps/web/src/lib/sync/syncExecutionLease.test.ts:129-332

**Interfaces:**
- Consumes: SyncSessionGuard.userId, owner_sync_leases in the central registry, Web Locks, and existing SyncLeaseUnavailableError/SyncLeaseLostError.
- Produces:

~~~ts
export interface SyncExecutionLeaseOptions {
  locks?: LockManager | null;
  now?: () => number;
  ownerToken?: () => string;
}

export interface SyncExecutionLease {
  readonly ownerToken: string;
  readonly signal: AbortSignal;
  assertOwned(): Promise<void>;
}

export function runWithOwnerSyncExecutionLease<T>(
  guard: SyncSessionGuard,
  task: (lease: SyncExecutionLease) => Promise<T>,
  options?: SyncExecutionLeaseOptions,
): Promise<T>;

export function runWithSyncExecutionLease<T>(
  guard: SyncSessionGuard,
  task: (lease: SyncExecutionLease) => Promise<T>,
  options?: SyncExecutionLeaseOptions,
): Promise<T>;
~~~

- [ ] **Step 1: Write cross-database lock tests in RED**

Extend syncExecutionLease.test.ts with two fake operational databases for the same owner. Start two paths under the same valid guard and make each task operate on a different explicit database handle. The second task must receive SyncLeaseUnavailableError while the first owns the owner lock, both with Web Locks and with locks: null:

~~~ts
const first = runWithOwnerSyncExecutionLease(
  firstGuard,
  async () => firstTaskGate.promise,
  { locks: null, ownerToken: () => "owner-a" },
);

await expect(runWithOwnerSyncExecutionLease(
  secondGuard,
  async () => "must-not-run",
  { locks: null, ownerToken: () => "owner-b" },
)).rejects.toBeInstanceOf(SyncLeaseUnavailableError);
~~~

Add a control case proving two different owner IDs can acquire independent locks. Retain expiry, heartbeat, lost-owner, cleanup-after-error, and session-switch tests. Prove the lease AbortSignal fires exactly once when heartbeat/ownership validation detects expiry or takeover, but not on a normal conditional release.

In `syncSession.test.ts`, use two online profiles with the same owner, scope,
name, role and expiration but different Plan 2 `sessionMarker` values. Assert
that their `syncSessionFingerprint` values differ and that a guard captured
under the first marker fails after `setSession` installs the second marker.

- [ ] **Step 2: Run the lease tests and record RED**

~~~bash
cd apps/web
npm test -- src/lib/sync/syncExecutionLease.test.ts src/lib/db/cortexNamespaceRegistry.test.ts
npm test -- src/lib/sync/syncSession.test.ts
~~~

Expected: FAIL because the fallback lease is still stored in each operational sync_state and the Web Lock name is global rather than owner-specific.

- [ ] **Step 3: Implement the owner-specific Web Lock and central lease**

Use the lock name cortex-sync-owner-v1: followed by guard.userId. The fallback helpers acquire, renew, assert, and release owner_sync_leases[guard.userId] transactionally in the central registry.

~~~ts
const WEB_LOCK_PREFIX = "cortex-sync-owner-v1:";

export async function runWithOwnerSyncExecutionLease<T>(
  guard: SyncSessionGuard,
  task: (lease: SyncExecutionLease) => Promise<T>,
  options: SyncExecutionLeaseOptions = {},
): Promise<T> {
  assertSyncSession(guard);
  const lockName = WEB_LOCK_PREFIX + guard.userId;
  // Web Lock if available, then central registry lease for cross-tab fallback.
}

export const runWithSyncExecutionLease =
  runWithOwnerSyncExecutionLease;
~~~

Do not read or write SyncStateRecord.syncExecutionLease in the new implementation. Leave the optional field readable for old databases, but central owner_sync_leases is the only authority after this task. Keep the current 45-second TTL, 15-second heartbeat, ownership checks before completion, and best-effort release semantics. Back the returned `signal` with one AbortController; abort it as soon as heartbeat or an explicit ownership check detects expiry/takeover/session invalidation, before rejecting with SyncLeaseLostError.

Include the opaque online session generation in the existing guard fingerprint:

~~~ts
export function syncSessionFingerprint(session: OnlineAuthProfile): string {
  return canonicalMutationJson({
    colaboradorId: session.colaboradorId,
    nome: session.nome,
    papelAcesso: session.papelAcesso,
    escopoGlobal: session.escopoGlobal,
    obraIds: [...session.obraIds].sort(),
    expiraEm: session.expiraEm,
    sessionMarker: session.sessionMarker,
  });
}
~~~

`captureOnlineSyncSession` obtains the profile through Plan 2's
`getOnlineSession()` boundary, never from the offline-profile union. The raw
marker remains in memory only; the registry and lock names contain owner ID or
the already-canonical guard fingerprint, never a persisted marker.

- [ ] **Step 4: Run lock and current-engine regressions in GREEN**

~~~bash
cd apps/web
npm test -- src/lib/sync/syncSession.test.ts src/lib/sync/syncExecutionLease.test.ts src/lib/sync/syncEngine.session.test.ts src/lib/sync/automaticSyncScheduler.test.ts
~~~

Expected: PASS; same-owner work from different databases is serialized, different owners are independent, and the current engine still treats contention as contention rather than a sync failure.

- [ ] **Step 5: Commit**

~~~bash
git add apps/web/src/lib/db/cortexNamespaceRegistry.ts apps/web/src/lib/db/cortexNamespaceRegistry.test.ts apps/web/src/lib/sync/syncSession.ts apps/web/src/lib/sync/syncSession.test.ts apps/web/src/lib/sync/syncExecutionLease.ts apps/web/src/lib/sync/syncExecutionLease.test.ts
git commit -m "feat(sync): serialize work with an owner lease"
~~~

---

### Task 3: Validate and Re-Register the Origin Device Without Mutating It

**Files:**
- Modify: apps/web/src/lib/sync/registerDevice.ts:14-90
- Create: apps/web/src/lib/sync/originDevice.ts
- Create: apps/web/src/lib/sync/originDevice.test.ts

**Interfaces:**
- Consumes: CortexDatabase, SyncSessionGuard, sync_state key default, registerDeviceApi(), and canonical v13 mutation fields.
- Produces:

~~~ts
export type OriginDeviceContext = {
  deviceId: string;
  userId: string;
};

export function assertOriginNamespaceOwner(
  database: CortexDatabase,
  guard: SyncSessionGuard,
): Promise<SyncStateRecord>;

export function assertOriginDeviceForMutations(
  mutations: readonly OutboxMutationRecord[],
  deviceId: string,
): void;

export function ensureOriginDeviceRegistered(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<OriginDeviceContext>;
~~~

- [ ] **Step 1: Write origin identity tests in RED**

Create cases for a valid same-owner state, missing state, null device, another usuarioId, server returning a different ID, and canonical rows whose outer deviceId or trace.deviceId diverges. `assertOriginNamespaceOwner` must reject a foreign/missing `sync_state.usuarioId` before any device registration or transport call:

~~~ts
await database.put("sync_state", {
  ...defaultState,
  deviceId: originDeviceId,
  usuarioId: guard.userId,
});

expect(await ensureOriginDeviceRegistered(database, guard, lease)).toEqual({
  deviceId: originDeviceId,
  userId: guard.userId,
});
expect(registerDeviceApi).toHaveBeenCalledWith(
  expect.objectContaining({
    id: originDeviceId,
    usuarioId: guard.userId,
  }),
);
expect(await database.get("sync_state", "default")).toEqual(
  expect.objectContaining({ deviceId: originDeviceId }),
);
~~~

For mismatch cases, assert registerDeviceApi is not called when local validation
fails and the original outbox status remains PENDING. Add object-only and
photo-only namespace cases with no generic outbox rows: an owner mismatch must
produce zero object/photo network requests and leave every row byte-for-byte
unchanged. Add a barrier after local origin validation but before
registerDeviceApi; lease takeover or session replacement must produce zero
registration calls and zero local writes.

- [ ] **Step 2: Run the focused test and record RED**

~~~bash
cd apps/web
npm test -- src/lib/sync/originDevice.test.ts
~~~

Expected: FAIL because no explicit origin-device helper exists and ensureRegisteredDevice always reads/writes the active namespace.

- [ ] **Step 3: Export the stable device label and implement fail-closed validation**

Export createDeviceName() from registerDevice.ts without changing active registration behavior. In originDevice.ts:

~~~ts
export async function ensureOriginDeviceRegistered(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<OriginDeviceContext> {
  assertSyncSession(guard);
  const state = await assertOriginNamespaceOwner(database, guard);
  if (
    typeof state.deviceId !== "string" ||
    !state.deviceId.trim()
  ) {
    throw new Error("O namespace anterior não comprova seu dispositivo de origem.");
  }

  await lease.assertOwned();
  assertSyncSession(guard);
  const response = await registerDeviceApi({
    id: state.deviceId,
    nome: createDeviceName(),
    tipo: "WEB",
    usuarioId: guard.userId,
  });
  assertSyncSession(guard);
  if (response.id !== state.deviceId) {
    throw new Error("O servidor não confirmou o dispositivo de origem.");
  }
  return { deviceId: state.deviceId, userId: guard.userId };
}
~~~

`assertOriginNamespaceOwner` reads only `sync_state["default"]`, rejects a
missing state or any `usuarioId !== guard.userId`, and revalidates the captured
session after the read. Task 7 calls it immediately after opening an old
database and before recovery, status inspection, OBJECT_UPLOAD, RDO_PHOTO, or
SYNC_PUSH. Device re-registration remains conditional on eligible generic
push, but owner proof is unconditional for all three transports.

`assertOriginDeviceForMutations` inspects every generic push candidate. For a
canonical v13 row, both `row.deviceId` and `row.trace.deviceId` must exist and
equal `originDeviceId`; a canonical mismatch fails closed into the registry's
safe review state before anything becomes SYNCING. Preserve the existing legacy
transport branch: a non-v13 row need not contain those canonical envelope
fields and is sent with only the request-level `dispositivoId` read from the
origin `sync_state`. It is never rewritten to fabricate v13 provenance. Add a
mixed legacy+v13 batch test plus the canonical mismatch control, and validate
the complete selected batch before marking any row SYNCING.

- [ ] **Step 4: Run origin and active-device regressions in GREEN**

~~~bash
cd apps/web
npm test -- src/lib/sync/originDevice.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/syncEngine.session.test.ts
~~~

Expected: PASS; an origin device is re-registered exactly, active registration remains unchanged, and no helper writes an old cursor or substitutes a new ID.

- [ ] **Step 5: Commit**

~~~bash
git add apps/web/src/lib/sync/registerDevice.ts apps/web/src/lib/sync/originDevice.ts apps/web/src/lib/sync/originDevice.test.ts
git commit -m "feat(sync): preserve the origin device identity"
~~~

---

### Task 4: Make Generic SYNC_PUSH Operate on an Explicit Database

**Files:**
- Modify: apps/web/src/lib/db/outboxRepository.ts:8-87
- Modify: apps/web/src/lib/sync/syncStorage.ts:1361-1498, 3157-4000, 4003-4327, 4328-4505, 4664-4810, 5030-5248
- Modify: apps/web/src/lib/sync/pushOutbox.ts:1-310
- Modify: apps/web/src/lib/sync/pushOutbox.test.ts
- Modify: apps/web/src/lib/sync/syncStorage.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.ts
- Modify: apps/web/src/lib/sync/syncEngine.session.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.auth.test.ts
- Modify: apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts
- Modify: apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts
- Test: apps/web/src/lib/sync/podaDaOutbox.test.ts
- Test: apps/web/src/lib/sync/falhaDeEnvioNaoEhRecusa.test.ts
- Modify: apps/web/src/features/home/memory/memoryReconciliation.test.ts

**Interfaces:**
- Consumes: CortexDatabase, SyncSessionGuard, origin device ID, selectReadyOutboxMutations(), pushMutationsApi(), and current atomic result/retry/conflict rules.
- Produces:

~~~ts
export function listReadyPendingOutboxMutationsFrom(
  database: CortexDatabase,
  limit?: number,
): Promise<OutboxMutationRecord[]>;

export function getOutboxMutationFrom(
  database: CortexDatabase,
  clientMutationId: string,
): Promise<OutboxMutationRecord | undefined>;

export type PushOutboxContext = {
  database: CortexDatabase;
  deviceId: string;
  guard: SyncSessionGuard;
  lease: SyncExecutionLease;
  limit?: number;
};

export function pushOutboxFromDatabase(
  context: PushOutboxContext,
): Promise<PushOutboxSummary>;

export function recoverInterruptedMutationsIn(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<number>;

export function markMutationAsSyncingIn(
  database: CortexDatabase,
  mutation: OutboxMutationRecord,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<OutboxMutationRecord>;

export function applyPushResultAtomicallyIn(
  database: CortexDatabase,
  result: SyncPushMutationResult,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void>;

export function reconcileCanonicalConflictIn(
  database: CortexDatabase,
  clientMutationId: string,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
  replacementMutationId: string,
  replacementEventId: string,
  occurredAt: string,
): Promise<CanonicalOutboxMutationRecord | null>;

export function recoverCanonicalConflictReconciliationsIn(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
  options?: CanonicalConflictRecoveryOptions,
): Promise<number>;

export function resolveCanonicalUploadReplacementsIn(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
  options?: CanonicalUploadReplacementOptions,
): Promise<number>;

export function rejectMutationLocallyIn(
  database: CortexDatabase,
  clientMutationId: string,
  safeCode: string,
  message: string,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void>;

export function returnMutationToPendingIn(
  database: CortexDatabase,
  clientMutationId: string,
  errorMessage: string,
  safeCode: string,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void>;

export function pruneAppliedMutationsIn(
  database: CortexDatabase,
  appliedMutationIds: ReadonlySet<string>,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<number>;

export function prunePreviouslyAppliedMutationsIn(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<number>;
~~~

- [ ] **Step 1: Write an explicit-old-database push test in RED**

Prepare an empty active database and a previous database containing two ordered PENDING rows with known IDs/payloads. Give the previous sync_state an origin device. Assert the API request keeps order and data, applies APLICADA in the previous database, and never writes the active one:

~~~ts
const summary = await pushOutboxFromDatabase({
  database: previousDatabase,
  deviceId: originDeviceId,
  guard,
  lease,
  limit: 100,
});

expect(pushMutationsApi).toHaveBeenCalledWith({
  dispositivoId: originDeviceId,
  mutacoes: [
    expect.objectContaining({ clientMutationId: firstId, payload: firstPayload }),
    expect.objectContaining({ clientMutationId: secondId, payload: secondPayload }),
  ],
});
expect(summary.appliedMutationIds).toEqual([firstId, secondId]);
expect(await activeDatabase.count("outbox_mutations")).toBe(0);
~~~

Add mismatch coverage before the network call: one canonical row whose deviceId differs must leave every selected row PENDING and call pushMutationsApi zero times. Add APLICADA, CONFLITO/DESCARTADA, REJEITADA, retryable ERRO, nonretryable ERRO, missing server result, duplicate server result, local result-apply failure, session-change, and lease-loss cases against an explicit handle. Put a barrier after rows become SYNCING but before `/sync/push`; lease takeover or marker replacement must produce zero push calls. Use another barrier after the server response but before the first result transaction; replace the session marker or take over/expire the fallback lease and assert zero later origin writes. Add crash recovery: persist an APLICADA result as a generic SYNCED row, simulate closing before targeted prune, reopen, and prove `prunePreviouslyAppliedMutationsIn` removes it only after applying the same dependency/receipt safety checks. A surviving citation or retained integration receipt must preserve it.

- [ ] **Step 2: Run focused tests and record RED**

~~~bash
cd apps/web
npm test -- src/lib/sync/pushOutbox.test.ts src/lib/sync/podaDaOutbox.test.ts src/lib/sync/falhaDeEnvioNaoEhRecusa.test.ts
~~~

Expected: FAIL because repositories and syncStorage resolve the active database internally and pushOutbox has no explicit context.

- [ ] **Step 3: Add explicit repository/storage variants while preserving current wrappers**

Move each listed function body to its In variant and make the existing public function obtain getCortexDb() once before delegating. Do not add an optional database parameter whose omission could silently select the wrong namespace.

~~~ts
export async function markMutationAsSyncing(
  mutation: OutboxMutationRecord,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<OutboxMutationRecord> {
  return runWithOwnerSyncExecutionLease(guard, async (lease) =>
    markMutationAsSyncingIn(
      await getCortexDb(),
      mutation,
      guard,
      lease,
    ));
}

export async function markMutationAsSyncingIn(
  database: CortexDatabase,
  mutation: OutboxMutationRecord,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<OutboxMutationRecord> {
  await lease.assertOwned();
  assertSyncSession(guard);
  const guarded = guardSyncTransaction(
    database.transaction(RDO_SYNC_TRANSACTION_STORES, "readwrite"),
    guard,
    lease,
  );
  // Existing atomic state/event/entity logic remains unchanged.
}
~~~

Apply this exact split to recoverInterruptedMutations, markMutationAsSyncing,
applyPushResultAtomically, reconcileCanonicalConflict,
recoverCanonicalConflictReconciliations,
resolveCanonicalUploadReplacements, rejectMutationLocally,
returnMutationToPending, targeted pruning, and crash-recovery pruning. Sync-engine wrappers and every
explicit variant receive the caller's guard and lease. Every existing
current-namespace compatibility wrapper preserves its pre-plan public
signature and default guard; when invoked outside `executeSync`, it acquires
`runWithOwnerSyncExecutionLease` itself before delegating to the In variant.
Immediately before each readwrite transaction, call
`await lease.assertOwned()` followed by `assertSyncSession(guard)`, then open
`guardSyncTransaction` without another await. Keep guardedSyncTransaction
around every write.

In particular, preserve the existing manual `reconcileCanonicalConflict` call
shape used by Memory and other review actions. It must not require UI callers
to manufacture a lease or change `(clientMutationId) => Promise<...>`
callbacks. Its compatibility wrapper follows the same owner-lease pattern:

~~~ts
export async function reconcileCanonicalConflict(
  clientMutationId: string,
  replacementMutationId: string = crypto.randomUUID(),
  replacementEventId: string = crypto.randomUUID(),
  occurredAt: string = nowUtc(),
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<CanonicalOutboxMutationRecord | null> {
  return runWithOwnerSyncExecutionLease(guard, async (lease) =>
    reconcileCanonicalConflictIn(
      await getCortexDb(),
      clientMutationId,
      guard,
      lease,
      replacementMutationId,
      replacementEventId,
      occurredAt,
    ));
}
~~~

The push path already owns the lease and therefore calls only
`reconcileCanonicalConflictIn`; it must never nest the public wrapper. Extend
`memoryReconciliation.test.ts` to keep the one-argument injected callback
contract and add a syncStorage test proving the default/manual wrapper acquires
the owner lease before its write.

Likewise, `recoverCanonicalConflictReconciliationsIn` iterates the supplied
database and calls only `reconcileCanonicalConflictIn`. The active engine uses
that In variant under its existing lease. Preserve the public recovery wrapper
for standalone/manual callers by letting it acquire the owner lease once; add a
test that the active recovery path never attempts a nested reacquisition.

In this same task, update `executeSync` to resolve its current database once
and call the new In variants directly with the owner lease it already owns.
That includes interrupted recovery, canonical replacement, generic push/result
application, conflict handling, retry/rejection, and targeted pruning. It must
never call a compatibility wrapper that would attempt to reacquire the same
owner lock. This keeps Task 4 independently buildable before Tasks 5–7.

- [ ] **Step 4: Implement explicit push and preflight the complete batch**

`pushOutbox` remains the current-namespace compatibility wrapper with its
existing two-argument call shape and owns a lease only when called directly:

~~~ts
export async function pushOutbox(
  deviceId: string,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<PushOutboxSummary> {
  return runWithOwnerSyncExecutionLease(guard, async (lease) =>
    pushOutboxFromDatabase({
      database: await getCortexDb(),
      deviceId,
      guard,
      lease,
      limit: 100,
    }));
}
~~~

`executeSync` does not call that wrapper; it invokes
`pushOutboxFromDatabase({ database: currentDatabase, guard, lease, ... })`.
pushOutboxFromDatabase loads ready rows from the supplied handle, calls
assertOriginDeviceForMutations on the complete batch, then marks rows SYNCING.
After request serialization and immediately before `pushMutationsApi`, it calls
`await lease.assertOwned()` and `assertSyncSession(guard)` with no intervening
await.
Every subsequent get, retry, rejection, result application, and conflict
replacement uses the same handle. A batch-level HTTP failure returns unresolved
rows to PENDING or REJECTED according to the existing classifier before
rethrowing.

- [ ] **Step 5: Implement targeted safe pruning**

`pruneAppliedMutationsIn` may consider only IDs returned as APLICADA in this run and currently stored as SYNCED. Preserve identificadoresCitadosPor and the SOLICITACAO_INTEGRACAO receipt exception:

~~~ts
const candidates = all.filter((mutation) =>
  appliedMutationIds.has(mutation.clientMutationId) &&
  mutation.status === "SYNCED" &&
  !hasRetainedIntegrationReceipt(mutation)
);
~~~

Delete candidates in one transaction only when no surviving row cites them by dependsOnMutationIds, SUPERSEDED_BY, or causationId. `pruneAppliedMutationsIn` receives the same guard and lease, revalidates both immediately before opening its guarded delete transaction, and has a barrier test proving a marker change or lost lease before commit preserves every row. `prunePreviouslyAppliedMutationsIn` is the crash-recovery companion: it scans only generic SYNCED rows left by a previously durable APLICADA result, explicitly excludes OBJECT_UPLOAD, and applies the identical citation/receipt predicate in one guarded transaction. Run it before selecting the next generic batch in both active and previous-namespace paths. Never interpret `/sync/ack` as permission to prune.

- [ ] **Step 6: Run focused and current-path regressions in GREEN**

~~~bash
cd apps/web
npm test -- src/lib/sync/pushOutbox.test.ts src/lib/sync/syncStorage.test.ts src/lib/sync/podaDaOutbox.test.ts src/lib/sync/falhaDeEnvioNaoEhRecusa.test.ts src/lib/sync/canonicalUploadReplacement.test.ts src/lib/sync/conflitoSemLojaPrincipal.test.ts src/lib/sync/syncEngine.session.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/reparoNaoDerrubaOCiclo.test.ts src/lib/sync/syncMemoryOfflineFlow.test.ts src/features/home/memory/memoryReconciliation.test.ts
npm run build
~~~

Expected: PASS; explicit old writes stay old, canonical mismatch fails before SYNCING, APLICADA is the only pruning input, and the active wrapper retains all prior behavior.

- [ ] **Step 7: Commit**

~~~bash
git add apps/web/src/lib/db/outboxRepository.ts apps/web/src/lib/sync/syncStorage.ts apps/web/src/lib/sync/pushOutbox.ts apps/web/src/lib/sync/pushOutbox.test.ts apps/web/src/lib/sync/syncStorage.test.ts apps/web/src/lib/sync/podaDaOutbox.test.ts apps/web/src/lib/sync/falhaDeEnvioNaoEhRecusa.test.ts apps/web/src/lib/sync/syncEngine.ts apps/web/src/lib/sync/syncEngine.session.test.ts apps/web/src/lib/sync/syncEngine.auth.test.ts apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts apps/web/src/features/home/memory/memoryReconciliation.test.ts
git commit -m "refactor(sync): push through explicit database handles"
~~~

---

### Task 5: Preserve OBJECT_UPLOAD and mensagem_anexos in Their Origin Database

**Files:**
- Modify: apps/web/src/features/mensagens/objectUploadSync.ts:34-343
- Modify: apps/web/src/features/mensagens/objectUploadSync.test.ts
- Modify: apps/web/src/features/mensagens/objectUploadSync.session.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.ts
- Modify: apps/web/src/lib/sync/syncEngine.session.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.auth.test.ts
- Modify: apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts
- Modify: apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts
- Create: apps/web/src/lib/sync/crossNamespaceReconciler.objectUpload.test.ts

**Interfaces:**
- Consumes: CortexDatabase, SyncSessionGuard, SyncExecutionLease, OBJECT_UPLOAD outbox rows, mensagem_anexos Blob/hash/size, /objetos, and guarded origin transactions.
- Produces:

~~~ts
export interface ObjectUploadSummary {
  pushed: number;
  applied: number;
  errors: number;
  retryableErrors: number;
  terminalErrors: number;
  confirmedMutationIds: string[];
}

export function processObjectUploadsFromDatabase(
  database: CortexDatabase,
  limit: number,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<ObjectUploadSummary>;

export function pruneConfirmedObjectUploadsIn(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<number>;
~~~

- [ ] **Step 1: Write the object-only previous-namespace test in RED**

Create an old namespace containing only a PENDING OBJECT_UPLOAD mutation and matching mensagem_anexos Blob. Leave the active namespace empty. Mock /objetos with the same hash and size, and make the generic push mock throw if called:

~~~ts
const summary = await processObjectUploadsFromDatabase(
  previousDatabase,
  20,
  guard,
  lease,
);

expect(summary).toEqual({
  pushed: 1,
  applied: 1,
  errors: 0,
  retryableErrors: 0,
  terminalErrors: 0,
  confirmedMutationIds: [uploadId],
});
expect(pushMutationsApi).not.toHaveBeenCalled();
expect(await previousDatabase.get("outbox_mutations", uploadId))
  .toEqual(expect.objectContaining({ status: "SYNCED" }));
expect(await previousDatabase.get("mensagem_anexos", attachmentId))
  .toEqual(expect.objectContaining({
    objetoId: storedObjectId,
    sha256,
    syncStatus: "SINCRONIZADO",
  }));
~~~

Add integrity mismatch, retryable network failure, terminal 4xx, missing Blob/hash, dependent legacy payload replacement, canonical replacement blocking, and session switch cases. Each must update only the supplied database. Put one barrier after candidate selection but before the initial transaction that marks the outbox/attachment SYNCING; session replacement or lease takeover at that point must leave both rows byte-for-byte unchanged. Keep the existing post-response barriers for success and failure result transactions. Add a crash-after-durable-success-before-prune case and an upload-success-with-dependent-generic-mutation case. The first is pruned safely on the next maintenance call; the second is retained until its dependent mutation is durably APLICADA/pruned, then becomes prunable.

- [ ] **Step 2: Run object transport tests and record RED**

~~~bash
cd apps/web
npm test -- src/features/mensagens/objectUploadSync.test.ts src/features/mensagens/objectUploadSync.session.test.ts src/lib/sync/crossNamespaceReconciler.objectUpload.test.ts
~~~

Expected: FAIL because the exported processor calls getCortexDb() before it reaches the already database-scoped internal helpers.

- [ ] **Step 3: Add the explicit processor and keep the active wrapper**

~~~ts
export async function processObjectUploads(
  limit = 20,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<ObjectUploadSummary> {
  return runWithOwnerSyncExecutionLease(guard, async (lease) =>
    processObjectUploadsFromDatabase(
      await getCortexDb(),
      limit,
      guard,
      lease,
    ));
}

export async function processObjectUploadsFromDatabase(
  database: CortexDatabase,
  limit: number,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<ObjectUploadSummary> {
  await lease.assertOwned();
  assertSyncSession(guard);
  const candidates = selectObjectUploadCandidates(
    await database.getAll("outbox_mutations"),
    limit,
  );
  // processOneUpload, applyUploadedObject, and markUploadFailed receive database.
}
~~~

In this task, switch `executeSync` from that compatibility wrapper to
`processObjectUploadsFromDatabase(currentDatabase, 20, guard, lease)`, using
the current handle and owner lease already established by Task 4. This avoids a
nested lock and keeps the Task 5 commit independently buildable.

Keep the existing atomic success transaction: outbox status SYNCED, attachment object ID/hash and SINCRONIZADO, plus dependent resolution in the origin database. Keep transient failure as scheduled PENDING/NA_FILA and terminal refusal as REJECTED/FALHOU. Immediately before **every** readwrite transaction — including the initial PENDING-to-SYNCING transition and every success or failure result — call `await lease.assertOwned()` followed by `assertSyncSession(guard)`, then open `guardSyncTransaction` with no intervening await. Candidate reads never authorize a later write. The pre-SYNCING and post-response barriers prove session replacement and fallback-lease takeover both leave origin rows byte-for-byte unchanged.

After reading/hashing the Blob and serializing the upload body, revalidate
`lease` and `guard` again immediately before the `/objetos` request. Add a
takeover/session barrier at that exact point and assert the request mock remains
at zero; the earlier SYNCING state may be recovered by a later valid run, but a
stale run cannot create a remote object.

`confirmedMutationIds` contains only OBJECT_UPLOAD rows whose upload integrity
and origin attachment state were committed as SYNCED/SINCRONIZADO in this run.
`pruneConfirmedObjectUploadsIn` also discovers older SYNCED OBJECT_UPLOAD rows
left by a crash or previous version, but deletes one only when the matching
mensagem_anexos row durably carries the server object ID/hash, no surviving
mutation cites it by dependency/causation/supersession, and it is not a retained
integration receipt. It receives guard+lease, performs the final validation and
delete in one guarded transaction, and never consumes `/sync/ack` or generic
APLICADA IDs as proof. This own confirmation/poda path prevents an unbounded
collection of successful binary-upload envelopes while retaining any row still
needed to rewrite or audit a dependent generic mutation.

- [ ] **Step 4: Run object transport and messaging regressions in GREEN**

~~~bash
cd apps/web
npm test -- src/features/mensagens/objectUploadSync.test.ts src/features/mensagens/objectUploadSync.session.test.ts src/lib/sync/crossNamespaceReconciler.objectUpload.test.ts src/features/mensagens/mensagensRepository.test.ts src/lib/sync/syncEngine.session.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/reparoNaoDerrubaOCiclo.test.ts src/lib/sync/syncMemoryOfflineFlow.test.ts
npm run build
~~~

Expected: PASS; the object-only namespace completes through /objetos, never enters generic push, and every success/failure state is durable in the origin database.

- [ ] **Step 5: Commit**

~~~bash
git add apps/web/src/features/mensagens/objectUploadSync.ts apps/web/src/features/mensagens/objectUploadSync.test.ts apps/web/src/features/mensagens/objectUploadSync.session.test.ts apps/web/src/lib/sync/crossNamespaceReconciler.objectUpload.test.ts apps/web/src/lib/sync/syncEngine.ts apps/web/src/lib/sync/syncEngine.session.test.ts apps/web/src/lib/sync/syncEngine.auth.test.ts apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts
git commit -m "refactor(sync): upload message objects from origin databases"
~~~

---

### Task 6: Preserve RDO Photo Upload and Server Linking Without an Outbox

**Files:**
- Modify: apps/web/src/lib/db/rdoAttachmentRepository.ts:1-102
- Create: apps/web/src/lib/db/rdoAttachmentRepository.test.ts
- Modify: apps/web/src/features/rdos/rdoPhotoSync.ts:72-180
- Modify: apps/web/src/features/rdos/rdoPhotoSync.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.ts
- Modify: apps/web/src/lib/sync/syncEngine.session.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.auth.test.ts
- Modify: apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts
- Modify: apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts
- Test: apps/web/src/features/rdos/RdoCreatePage.sincronizaOQueEstaNaTela.test.tsx
- Test: apps/web/src/features/rdos/importRdoExcel.pdf.test.ts
- Create: apps/web/src/lib/sync/crossNamespaceReconciler.rdoPhoto.test.ts

**Interfaces:**
- Consumes: CortexDatabase, SyncSessionGuard, SyncExecutionLease, rdo_attachments Blob/hash, local RDO version, /objetos, and PUT /rdos/{rdoId}/anexos/{attachmentId}/objeto.
- Produces:

~~~ts
export function putRdoAttachmentIn(
  database: CortexDatabase,
  attachment: RdoAttachmentRecord,
): Promise<void>;

export type RdoAttachmentSyncResult = {
  id: string;
  expectedUpdatedAt: string;
  expectedRemovedAt: string | null;
  patch: Pick<
    RdoAttachmentRecord,
    "storedObjectId" | "sha256" | "syncStatus" | "ultimoErro" | "updatedAt"
  >;
};

export function putRdoAttachmentSyncResultIn(
  database: CortexDatabase,
  result: RdoAttachmentSyncResult,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<"WRITTEN" | "STALE">;

export function processRdoPhotoUploadsFromDatabase(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
  limit?: number,
): Promise<RdoPhotoUploadSummary>;

export interface RdoPhotoUploadSummary {
  enviadas: number;
  falhas: number;
  retryableFailures: number;
  terminalFailures: number;
}
~~~

- [ ] **Step 1: Write the photo-only previous-namespace test in RED**

Create a previous database with one server-versioned RDO and one PENDING_SYNC rdo_attachments row containing a Blob, with no outbox rows. Assert upload, link, and origin durability:

~~~ts
const summary = await processRdoPhotoUploadsFromDatabase(
  previousDatabase,
  guard,
  lease,
  3,
);

expect(summary).toEqual({
  enviadas: 1,
  falhas: 0,
  retryableFailures: 0,
  terminalFailures: 0,
});
expect(objectUploadRequest).toHaveBeenCalledTimes(1);
expect(rdoLinkRequest).toHaveBeenCalledWith(
  expect.stringContaining("/rdos/" + rdoId + "/anexos/" + attachmentId + "/objeto"),
  expect.anything(),
);
expect(await previousDatabase.get("rdo_attachments", attachmentId))
  .toEqual(expect.objectContaining({
    storedObjectId,
    sha256,
    syncStatus: "SYNCED",
  }));
expect(await activeDatabase.get("rdo_attachments", attachmentId))
  .toBeUndefined();
~~~

Add cases for hash mismatch, upload success plus link 404, retryable 408/429/5xx/network error, definitive 4xx, removed photo, missing Blob, session switch, and lease loss. A local PENDING_SYNC row with `storedObjectId === null`, `removedAt === null`, and a missing Blob must make zero requests and go through the guarded result writer to `SYNC_FAILED`, incrementing terminalFailures/review. A server-seeded row with a storedObjectId and no Blob remains a valid cache miss and is not selected as upload work. Put barriers immediately before `/objetos`, immediately before the mutating RDO-link PUT, and after the link response before the attachment transaction; replacing the session marker or taking over/expiring the fallback lease must prevent the corresponding later network call/write. A link failure must not mark SYNCED. Add a link-response barrier where the user removes the photo locally before the result transaction: the writer returns STALE, preserves removedAt/newer updatedAt, and never resurrects or marks the row SYNCED.

In `rdoAttachmentRepository.test.ts`, prove the local write boundary remains
usable with no online session or sync lease:

~~~ts
await putRdoAttachment(localAttachment);

expect(await getRdoAttachment(localAttachment.id)).toEqual(localAttachment);
~~~

Add one offline photo-creation regression to
`RdoCreatePage.sincronizaOQueEstaNaTela.test.tsx` and one offline photo-import
regression to `importRdoExcel.pdf.test.ts`. With `navigator.onLine` false, each
path must still call the one-argument `putRdoAttachment(attachment)` and persist
the binary locally. These local-authoring writes deliberately do not require a
`SyncSessionGuard` or `SyncExecutionLease`; only a write derived from a network
sync result uses the guarded API below.

- [ ] **Step 2: Run photo tests and record RED**

~~~bash
cd apps/web
npm test -- src/lib/db/rdoAttachmentRepository.test.ts src/features/rdos/rdoPhotoSync.test.ts src/lib/sync/crossNamespaceReconciler.rdoPhoto.test.ts src/features/rdos/RdoCreatePage.sincronizaOQueEstaNaTela.test.tsx src/features/rdos/importRdoExcel.pdf.test.ts
~~~

Expected: the existing local create/import assertions remain GREEN, while the
explicit old-namespace and guarded network-result assertions fail because
processRdoPhotoUploads and its result writes still resolve the active database
implicitly.

- [ ] **Step 3: Implement explicit photo persistence and processing**

~~~ts
export async function putRdoAttachmentIn(
  database: CortexDatabase,
  attachment: RdoAttachmentRecord,
): Promise<void> {
  await database.put("rdo_attachments", attachment);
}

export async function putRdoAttachment(
  attachment: RdoAttachmentRecord,
): Promise<void> {
  return putRdoAttachmentIn(await getCortexDb(), attachment);
}

export async function putRdoAttachmentSyncResultIn(
  database: CortexDatabase,
  result: RdoAttachmentSyncResult,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<"WRITTEN" | "STALE"> {
  await lease.assertOwned();
  assertSyncSession(guard);
  const guarded = guardSyncTransaction(
    database.transaction("rdo_attachments", "readwrite"),
    guard,
    lease,
  );
  try {
    const current = await guarded.transaction.store.get(result.id);
    if (
      !current ||
      current.updatedAt !== result.expectedUpdatedAt ||
      (current.removedAt ?? null) !== result.expectedRemovedAt
    ) {
      await guarded.complete();
      return "STALE";
    }
    await guarded.transaction.store.put({ ...current, ...result.patch });
    await guarded.complete();
    return "WRITTEN";
  } finally {
    guarded.dispose();
  }
}

export async function processRdoPhotoUploads(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<RdoPhotoUploadSummary> {
  return runWithOwnerSyncExecutionLease(guard, async (lease) =>
    processRdoPhotoUploadsFromDatabase(
      await getCortexDb(),
      guard,
      lease,
      FOTOS_POR_CICLO,
    ));
}
~~~

In this task, switch `executeSync` from that compatibility wrapper to
`processRdoPhotoUploadsFromDatabase(currentDatabase, guard, lease,
FOTOS_POR_CICLO)`. It reuses the current handle and already-owned lease, so the
Task 6 commit builds without nested lock acquisition before Task 7.

The explicit processor uses only the supplied handle for candidate selection,
RDO lookup, and every attachment result put. It selects every local nonremoved
row with no storedObjectId before inspecting the Blob. If the Blob is absent,
it performs no HTTP call and persists a terminal SYNC_FAILED result through
`putRdoAttachmentSyncResultIn`; only server-seeded rows that already carry a
storedObjectId remain outside this upload classifier.
`putRdoAttachmentSyncResultIn` calls `await lease.assertOwned()` and
`assertSyncSession(guard)` immediately before opening a
`guardSyncTransaction` over the supplied database; every SYNCED,
pending-retry, and SYNC_FAILED write derived from upload/link responses flows
through that guarded contract. Preserve `putRdoAttachment` and
`putRdoAttachmentIn` as local-authoring APIs with no guard/lease, so
RdoCreatePage, Excel/PDF import, pull seeding, and on-demand local blob caching
continue to work offline. Never call an unguarded local-authoring API from the
network-result branch of `processRdoPhotoUploadsFromDatabase`. Preserve the
existing definitive-rejection classifier: 404 remains retryable because the
RDO or attachment record may not yet exist; 408 and 429 remain retryable;
other 4xx become SYNC_FAILED; transient failures keep the binary and pending
state. After Blob/hash/body preparation, revalidate lease/session immediately
before `/objetos`; after that response and link-body preparation, repeat the
check immediately before the attachment-link PUT. The processor increments
`enviadas` only for WRITTEN; STALE means a newer local edit/removal won and is
neither retried nor overwritten.
Classify every failure into exactly one of `retryableFailures` or
`terminalFailures`; `falhas` is their sum. The reconciler schedules registry
backoff only from the retryable counter. Terminal failures remain visible in
review with no retry deadline.

- [ ] **Step 4: Prove the required two-cycle RDO ordering**

Add a test where the old RDO has versaoEntidade null and both its generic create row and photo are pending. Cycle one must skip the photo, apply the RDO through generic push, and report legacy work remaining. Cycle two may upload/link the photo after the durable RDO version exists. This test protects the mandated object → photo → generic ordering without pretending a pre-server RDO photo can link in the same cycle.

- [ ] **Step 5: Run photo and RDO regressions in GREEN**

~~~bash
cd apps/web
npm test -- src/lib/db/rdoAttachmentRepository.test.ts src/features/rdos/rdoPhotoSync.test.ts src/lib/sync/crossNamespaceReconciler.rdoPhoto.test.ts src/features/rdos/RdoCreatePage.sincronizaOQueEstaNaTela.test.tsx src/features/rdos/importRdoExcel.pdf.test.ts src/features/rdos/rdosDoServidorAparecemNoAparelho.test.ts src/features/rdos/rdoLocalPendingCreation.test.ts
npm test -- src/lib/sync/syncEngine.session.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/reparoNaoDerrubaOCiclo.test.ts src/lib/sync/syncMemoryOfflineFlow.test.ts
npm run build
~~~

Expected: PASS; a photo-only namespace is processed, success requires upload
plus link plus durable guarded local state, pre-server RDO photos wait without
being lost, and ordinary photo creation/import still persists offline without
an online guard or lease.

- [ ] **Step 6: Commit**

~~~bash
git add apps/web/src/lib/db/rdoAttachmentRepository.ts apps/web/src/lib/db/rdoAttachmentRepository.test.ts apps/web/src/features/rdos/rdoPhotoSync.ts apps/web/src/features/rdos/rdoPhotoSync.test.ts apps/web/src/features/rdos/RdoCreatePage.sincronizaOQueEstaNaTela.test.tsx apps/web/src/features/rdos/importRdoExcel.pdf.test.ts apps/web/src/lib/sync/crossNamespaceReconciler.rdoPhoto.test.ts apps/web/src/lib/sync/syncEngine.ts apps/web/src/lib/sync/syncEngine.session.test.ts apps/web/src/lib/sync/syncEngine.auth.test.ts apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts
git commit -m "refactor(sync): reconcile RDO photos in origin databases"
~~~

---

### Task 7: Orchestrate One Previous Namespace After the Active Sync

**Files:**
- Modify: apps/web/src/lib/db/cortexNamespaceRegistry.ts
- Modify: apps/web/src/lib/db/cortexNamespaceRegistry.test.ts
- Modify: apps/web/src/lib/db/syncStateRepository.ts
- Create: apps/web/src/lib/db/syncStateRepository.session.test.ts
- Modify: apps/web/src/lib/db/localRdoService.ts
- Modify: apps/web/src/lib/db/localRdoService.test.ts
- Create: apps/web/src/lib/sync/crossNamespaceReconciler.ts
- Create: apps/web/src/lib/sync/crossNamespaceReconciler.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.ts:44-384
- Modify: apps/web/src/lib/sync/syncEngine.session.test.ts
- Modify: apps/web/src/lib/sync/syncEngine.auth.test.ts
- Modify: apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts
- Modify: apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts
- Modify: apps/web/src/lib/sync/guardedSyncTransaction.ts
- Create: apps/web/src/lib/sync/guardedSyncTransaction.test.ts
- Modify: apps/web/src/lib/sync/syncStorage.ts
- Modify: apps/web/src/lib/sync/syncStorage.test.ts
- Modify: apps/web/src/lib/sync/syncStorage.task3.test.ts
- Modify: apps/web/src/lib/sync/syncStorage.reidentificacao.test.ts
- Modify: apps/web/src/lib/sync/pullEvents.ts
- Modify: apps/web/src/lib/sync/pullEventsJanelaCheia.test.ts
- Modify: apps/web/src/lib/sync/ackCursor.ts
- Modify: apps/web/src/lib/sync/registerDevice.ts
- Modify: apps/web/src/lib/sync/registerDevice.auth.test.ts
- Modify: apps/web/src/lib/sync/syncTransportSession.test.ts
- Modify: apps/web/src/features/mensagens/mensagensHydration.ts
- Modify: apps/web/src/features/mensagens/mensagensHydration.session.test.ts
- Modify: apps/web/src/features/mensagens/mensagensRepository.ts
- Modify: apps/web/src/features/mensagens/mensagensRepository.test.ts
- Modify: apps/web/src/lib/sync/sync.types.ts:107-150
- Modify: apps/web/src/lib/sync/automaticSyncScheduler.ts:5-145
- Modify: apps/web/src/lib/sync/automaticSyncScheduler.test.ts
- Modify: apps/web/src/lib/sync/useAutomaticSync.ts:8-54
- Modify: apps/web/src/lib/sync/janelaCheiaPedeOutra.test.ts

**Interfaces:**
- Consumes: owner-global SyncExecutionLease, current database name, registry rows, explicit CortexDatabase handles, the three database-scoped processors, and SyncSessionGuard.
- Produces:

~~~ts
export type PreviousNamespaceReconciliationSummary = {
  namespacesVisited: number;
  syncPushApplied: number;
  objectUploadsApplied: number;
  rdoPhotosApplied: number;
  conflicts: number;
  rejected: number;
  errors: number;
  hasMore: boolean;
};

export function reconcilePreviousNamespaces(
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<PreviousNamespaceReconciliationSummary>;

export type NamespaceReconciliationOutcome = {
  retryAttempt: number;
  nextAttemptAt: string | null;
  lastReconciledAt: string;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
};

export type NamespaceReconciliationStatePatch = {
  snapshot?: NamespaceStatusSnapshot;
  outcome?: NamespaceReconciliationOutcome;
};

export function commitNamespaceReconciliationState(
  databaseName: string,
  patch: NamespaceReconciliationStatePatch,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<DataNamespaceRecord>;

export function refreshNamespaceStatusForSync(
  databaseName: string,
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<DataNamespaceRecord>;

export function recordNamespaceReconciliationOutcomeForSync(
  databaseName: string,
  outcome: NamespaceReconciliationOutcome,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<DataNamespaceRecord>;

export function updateSyncStateForSync(
  database: CortexDatabase,
  patch: Partial<SyncStateRecord>,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void>;

export function ensureRegisteredDevice(
  database: CortexDatabase,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<string>;

export function pullEvents(
  database: CortexDatabase,
  deviceId: string,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<PullEventsSummary>;

export function acknowledgeCurrentCursor(
  database: CortexDatabase,
  deviceId: string,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<number>;

export function refreshMessagingAfterPull(
  database: CortexDatabase,
  conversationIds: string[],
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void>;

export function applyPulledEventsAtomicallyIn(
  database: CortexDatabase,
  events: SyncPullEvent[],
  nextCommitSeq: number,
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<number>;

export function storeServerConversationsForSync(
  database: CortexDatabase,
  conversations: ConversationApi[],
  options: { authoritative?: boolean },
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void>;

export function storeServerMessagesForSync(
  database: CortexDatabase,
  messages: MessageApi[],
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void>;

export interface SyncRunSummary {
  // Existing fields remain.
  previousNamespaces: PreviousNamespaceReconciliationSummary;
}
~~~

This task also extends `guardSyncTransaction` with an optional required-for-sync
lease argument. The wrapper attaches both the existing auth-session listener
and `lease.signal` before returning the transaction handle, aborts immediately
if either is already invalid, and removes both listeners on complete/dispose.
Non-sync local transactions may keep the existing two-argument overload; every
`...ForSync`/`...In` writer in this plan uses the three-argument form. A central
`guardedSyncTransaction.test.ts` pauses a live readwrite transaction, aborts the
lease signal, and proves the transaction rejects and leaves all stores
byte-for-byte unchanged.

- [ ] **Step 1: Write the full bounded reconciler test in RED**

Register currentName, previousNameA, and previousNameB for one owner. Put an OBJECT_UPLOAD, eligible RDO photo, and two ordered generic mutations in A; put one generic mutation in B. Assert one call:

~~~ts
const summary = await reconcilePreviousNamespaces(guard, lease);

expect(transportOrder).toEqual([
  "OBJECT_UPLOAD",
  "RDO_PHOTO",
  "SYNC_PUSH",
]);
expect(summary.namespacesVisited).toBe(1);
expect(summary.hasMore).toBe(true);
expect(openedDatabaseNames).toEqual([previousNameA]);
expect(pullEvents).not.toHaveBeenCalled();
expect(acknowledgeCurrentCursor).not.toHaveBeenCalled();
~~~

Assert the push request uses previousNameA sync_state.deviceId; payload, IDs, dependency order, correlation/causation, and hashes are unchanged. Assert only APLICADA IDs are considered by pruneAppliedMutationsIn and a generic SYNCED row left by crash is recovered only by prunePreviouslyAppliedMutationsIn. A second call must visit B.

Add fail-closed tests for registry owner mismatch, sync_state.usuarioId mismatch, missing origin device with generic work, canonical device mismatch, session marker change, owner change, lost lease, and 401 that clears the online session. In every case, remaining origin rows and attachments must still exist.

Add registry-write barriers in `cortexNamespaceRegistry.test.ts` and the
reconciler test. Pause after the old database has been inspected but before the
central registry transaction. Replacing the session marker or losing/taking
over the owner lease must prevent every later aggregate-count, retry,
nextAttemptAt, lastReconciledAt, and diagnostic write. The registry row remains
byte-for-byte unchanged. Run the same barrier for a clean/progress outcome, an
ordinary transport failure, and NAMESPACE_NOT_FOUND so success, retry, and
missing-database bookkeeping all prove the invariant.

Update `syncEngine.auth.test.ts`, `reparoNaoDerrubaOCiclo.test.ts`, and
`syncMemoryOfflineFlow.test.ts` so their module mocks export every new
database-scoped processor/repair used by executeSync. Assert executeSync passes
one current handle, guard, and the already-owned lease; none of these harnesses
may receive an undefined mock or observe nested lease acquisition.

Extend `syncTransportSession.test.ts` with the same lease-takeover barriers as
the session-change cases: before device registration and `/sync/ack`, the
mutating request count remains zero; after device-registration, pull, or ack
responses, no `sync_state` or pulled-event transaction commits. In
`mensagensHydration.session.test.ts`, lose the lease after each GET response and
prove no authoritative conversation/message write runs. In
`syncStateRepository.session.test.ts`, lose the lease before the transaction
and during its guarded lifetime; both leave the state unchanged. Add one
barrier to every active repair family in localRdoService/syncStorage tests so a
repair selected under an old lease cannot commit after takeover.

Add a second-visit journey: reconcile A to SCANNED/zero; activate B; reactivate A,
which marks it UNKNOWN; create new offline work in A; activate B again; then
assert the next bounded reconciliation opens A and applies that new work. This
must pass even though A's older aggregate snapshot was zero.

- [ ] **Step 2: Run reconciler and engine tests and record RED**

~~~bash
cd apps/web
npm test -- src/lib/sync/crossNamespaceReconciler.test.ts src/lib/sync/syncEngine.session.test.ts src/lib/sync/janelaCheiaPedeOutra.test.ts
~~~

Expected: FAIL because no reconciler or previousNamespaces summary exists and current sync announces completion before old-namespace state is durable.

- [ ] **Step 3: Implement eligibility, one-namespace budgeting, and safe failure recording**

Exclude the exact currentDataDatabaseName. Select only same-owner, nonterminal rows whose scanState is UNKNOWN or whose eligible counts contain work and whose nextAttemptAt is absent/due. `markDataNamespaceActive` from Task 1 is the authority that makes a revisited namespace UNKNOWN, so selection must not substitute a permanent SCANNED/zero shortcut. Sort by lastReconciledAt null first, then firstSeenAt, then databaseName. Before opening the selected row, parse its strict databaseName again and require `parsed.ownerId === row.ownerId === guard.userId`; a forged/stale registry row records the closed-set NAMESPACE_OWNER_MISMATCH through the guarded registry writer, sets scanState TERMINAL, and is never opened. Open only the first validated row with openExistingCortexDbByName and always close that explicit handle in finally. A missing database records NAMESPACE_NOT_FOUND with scanState TERMINAL only through `commitNamespaceReconciliationState`; it is never recreated. Apply the same terminal transition to ORIGIN_OWNER_MISMATCH, ORIGIN_DEVICE_MISSING, and CANONICAL_DEVICE_MISMATCH. Preserve origin rows for review, but prevent automatic reopen loops. Tests run a second cycle with stale eligible counts and a due deadline for every terminal code and assert zero database open and zero network.

~~~ts
const candidates = (await listOwnerNamespaces(guard.userId))
  .filter((row) =>
    row.databaseName !== currentName &&
    namespaceIsEligibleNow(row, now),
  )
  .sort(compareNamespacePriority);

const selected = candidates[0];
if (!selected) return emptyPreviousNamespaceSummary();
~~~

Immediately after opening the explicit handle, call
`assertOriginNamespaceOwner(database, guard)` before status inspection,
recovery, or any transport. Then call recoverInterruptedMutationsIn on the
supplied handle so a browser closed during SYNCING does not strand work. Refresh registry
counts before and after the attempt only through
`refreshNamespaceStatusForSync`.

`namespaceIsEligibleNow` must not strand a scheduled retry behind a stale
`eligible: 0` snapshot. Its exact reopening rules are:

~~~ts
if (row.scanState === "TERMINAL") return false;
if (row.nextAttemptAt && Date.parse(row.nextAttemptAt) > now) return false;
if (row.scanState === "UNKNOWN") return true;
if (hasEligibleTransport(row.eligible)) return true;
if (row.nextAttemptAt && hasPendingTransport(row.pending)) return true;
if (row.scanState === "ERROR" && row.nextAttemptAt) return true;
return false;
~~~

The fifth line reopens a generic/object/photo retry once its registry backoff is due
even when the last measured database snapshot was not yet eligible. The sixth
line retries an open/inspection error whose counts are unknown. A TERMINAL row
returns false before stale eligible counts or deadlines are considered. Add tests
where generic, object-upload, and transient photo failures schedule a retry: before the deadline
the database is not opened; after it, it is opened and applied despite
`eligible` remaining zero. Add the same due/not-due proof for a scan ERROR with
zero counts and the terminal missing-database control.

Also seed an UNKNOWN namespace whose only generic/object row already has a
future row-level `nextAttemptAt` from when it was active. Its first lazy scan may
open the database to measure it, must persist
`snapshot.earliestTransportAttemptAt` as the registry `nextAttemptAt` without
incrementing retryAttempt, and must not send it. Later cycles before the due
time do not reopen it; the first due cycle reopens and applies it. When both a
registry failure backoff and row-level schedule exist, commit the later safe
eligibility boundary so neither source is bypassed.

For an ordinary network/local transport failure, assert session and lease are still valid, increment registry retryAttempt, and set nextAttemptAt with bounded exponential backoff:

~~~ts
const baseDelayMs = Math.min(
  60_000 * (2 ** Math.min(retryAttempt, 6)),
  3_600_000,
);
const jitterMs = Math.floor(baseDelayMs * 0.2 * random());
const nextAttemptAt = new Date(now() + baseDelayMs + jitterMs).toISOString();
~~~

Inject now and random in reconciler test options. A returned
`objectSummary.retryableErrors > 0`,
`photoSummary.retryableFailures > 0`, or retryable generic failure schedules
the same registry backoff even when the processor handled the exception
internally. Terminal counters increment review but set `nextAttemptAt: null`
only for terminal-only work with no row-level future schedule. In a mixed batch,
any retryable result or `snapshot.earliestTransportAttemptAt` preserves a safe
future boundary while terminal results remain visible in review. The selected failed namespace is not immediately
eligible again, preventing a tight loop; `hasMore` is nevertheless true when a
different same-owner namespace remains immediately eligible, so one failure
does not delay B behind A's backoff.
Persist only the closed-set
safe diagnostic through `recordNamespaceReconciliationOutcomeForSync`, leave
origin rows in their transport-specific retry/review state, and return errors
without turning the already-completed active sync into failure. After progress,
reset retryAttempt but set nextAttemptAt to the later of any newly computed
retry backoff and the fresh `snapshot.earliestTransportAttemptAt` while pending
work remains; clear it only when no pending work/future boundary survives. A
clean scan with no pending work clears both. This prevents a ready row A from
erasing row B's future schedule in the same namespace. Add progress-plus-future
row and mixed terminal-plus-retryable regressions. If assertSyncSession or lease.assertOwned fails, propagate
immediately and do not write a stale diagnostic.

Both sync-only registry writers perform any old-database reads before the
commit boundary and delegate to `commitNamespaceReconciliationState`.
Immediately before opening the central registry readwrite transaction that
function executes, consecutively and with no intervening await:

~~~ts
await lease.assertOwned();
assertSyncSession(guard);
const guarded = guardSyncTransaction(
  registry.transaction(["namespaces", "owner_sync_leases"], "readwrite"),
  guard,
  lease,
);
~~~

Inside that guarded transaction, read `namespaces[databaseName]`, prove its
ownerId equals `guard.userId`, then read `owner_sync_leases[guard.userId]` and
prove its ownerToken equals `lease.ownerToken` and its expiry is still in the
future. Because the transaction includes both stores, no fallback takeover can
commit between that read and the namespace put. Merge only the supplied
snapshot/outcome, then await `guarded.complete()` and dispose in `finally` using
the existing `guardSyncTransaction` pattern. The owner lease is acquired even
when Web Locks are available, so this same token check covers both paths. No
await may occur between the final preflight session/lease validation and
opening the transaction. The unguarded discovery and
`markDataNamespaceActive` lifecycle APIs from Task 1 remain separate and are
never used to record a reconciliation result.

- [ ] **Step 4: Execute the three transports with their exact confirmations**

Within one selected handle:

~~~ts
const objectSummary = await processObjectUploadsFromDatabase(
  database,
  20,
  guard,
  lease,
);
await assertSyncExecution(guard, lease);

await resolveCanonicalUploadReplacementsIn(database, guard, lease);
await assertSyncExecution(guard, lease);
await pruneConfirmedObjectUploadsIn(database, guard, lease);
await assertSyncExecution(guard, lease);

const photoSummary = await processRdoPhotoUploadsFromDatabase(
  database,
  guard,
  lease,
  3,
);
await assertSyncExecution(guard, lease);

await prunePreviouslyAppliedMutationsIn(database, guard, lease);
await assertSyncExecution(guard, lease);

const readyPush = await listReadyPendingOutboxMutationsFrom(database, 100);
let pushSummary = emptyPushSummary();
if (readyPush.length > 0) {
  const origin = await ensureOriginDeviceRegistered(database, guard, lease);
  assertOriginDeviceForMutations(readyPush, origin.deviceId);
  pushSummary = await pushOutboxFromDatabase({
    database,
    deviceId: origin.deviceId,
    guard,
    lease,
    limit: 100,
  });
  await pruneAppliedMutationsIn(
    database,
    new Set(pushSummary.appliedMutationIds),
    guard,
    lease,
  );
  await pruneConfirmedObjectUploadsIn(database, guard, lease);
}
~~~

Do not import or call pullEvents, acknowledgeCurrentCursor, refreshMessagingAfterPull, or updateSyncState from crossNamespaceReconciler.ts. The old sync_state cursor remains byte-for-byte unchanged.

- [ ] **Step 5: Put current sync and reconciliation under the same owner lock**

Keep executeSync as the active-only operation. Move announceSyncCompleted out of executeSync. In syncNow:

~~~ts
const promise = runWithOwnerSyncExecutionLease(
  guard,
  async (lease) => {
    const current = await executeSync(guard, lease);
    const previousNamespaces =
      await reconcilePreviousNamespaces(guard, lease);
    await lease.assertOwned();
    assertSyncSession(guard);
    announceSyncCompleted();
    return { ...current, previousNamespaces };
  },
);
~~~

Resolve `currentDatabase` once inside that owner lease. The active
`executeSync` calls only explicit/database-scoped functions and passes that
same `currentDatabase`, `guard`, and `lease` through its entire lifecycle:

- initial/final/error state uses `updateSyncStateForSync` while the existing
  `updateSyncState` remains available for ordinary local feature writes;
- device registration calls `ensureRegisteredDevice(currentDatabase, guard,
  lease)` and revalidates immediately before registerDeviceApi and again before
  its guarded sync-state transaction;
- Task 4's interrupted recovery, missing obra/workforce reference repairs,
  nonexistent-obra reidentification, rejected geometry/archive recovery,
  errored-mutation retry scheduling, ghost-citation repair, upload replacement,
  conflict recovery, generic push/result writers, and both pruning families use
  explicit `...In(currentDatabase, guard, lease, ...)` variants;
- localRdoService's blocked-context hydration, blocked-update release,
  create-mutation repair, errored workforce recovery, and rejected-RDO recovery
  receive the same explicit context. Preserve their current public wrappers for
  non-engine callers, but the engine never calls a wrapper that acquires or
  captures another lease;
- object and photo processing use the FromDatabase variants introduced in
  Tasks 5–6;
- `pullEvents(currentDatabase, deviceId, guard, lease)` threads the lease into
  `applyPulledEventsAtomicallyIn`; before each pulled-event readwrite,
  revalidate lease/session and immediately open `guardSyncTransaction`;
- `refreshMessagingAfterPull(currentDatabase, ids, guard, lease)` performs its
  GETs, then uses only `storeServerConversationsForSync` and
  `storeServerMessagesForSync`, each with its own pre-transaction lease/session
  check and guarded transaction. The existing unguarded repository functions
  remain for non-sync UI/local writes;
- `acknowledgeCurrentCursor(currentDatabase, deviceId, guard, lease)` validates
  immediately before the mutating `/sync/ack` request and persists only through
  `updateSyncStateForSync` after revalidation.

For every local repair In variant, move any reads/computation before the commit
boundary; immediately before **each** readwrite transaction call
`await lease.assertOwned()` then `assertSyncSession(guard)` and open
`guardSyncTransaction` with no intervening await. Any helper called from an In
variant must call another In variant, never a compatibility wrapper; this
explicitly includes `recoverCanonicalConflictReconciliationsIn` and
`resolveCanonicalUploadReplacementsIn` calling
`reconcileCanonicalConflictIn`/`rejectMutationLocallyIn` only. Focused tests use
a deterministic fake lease whose `assertOwned()` can be blocked or failed at
every pre-network and commit boundary. A response from pull, registration,
messaging hydration, or ack after takeover leaves the current namespace
byte-for-byte unchanged.

This ordering guarantees Plan 2 receives SYNC_COMPLETED_EVENT only after the active state and registry/origin results are durable. Plan 2 may then renew the capsule independently; its failure must not alter the returned SyncRunSummary.

- [ ] **Step 6: Request another bounded cycle without a tight loop**

Add the scheduler trigger PREVIOUS_NAMESPACE_PENDING. Replace aindaFaltaPuxar with a predicate that returns the appropriate follow-up trigger:

~~~ts
export function proximoCicloDeSync(
  summary: unknown,
): "PULL_PENDENTE" | "PREVIOUS_NAMESPACE_PENDING" | null {
  if (hasBoolean(summary, "pullPendente")) return "PULL_PENDENTE";
  if (hasNestedBoolean(summary, "previousNamespaces", "hasMore")) {
    return "PREVIOUS_NAMESPACE_PENDING";
  }
  return null;
}
~~~

hasMore means another namespace or another immediately eligible bounded batch exists. It must be false when all remaining work is terminal review, blocked by dependency, or waiting for nextAttemptAt; those states rely on explicit user action, online/visibility, or the 30-second interval rather than recursive requests. Add a regression where one failed old namespace receives nextAttemptAt and does not schedule an immediate follow-up loop.

- [ ] **Step 7: Run orchestration and scheduler tests in GREEN**

~~~bash
cd apps/web
npm test -- src/lib/db/cortexNamespaceRegistry.test.ts src/lib/db/syncStateRepository.session.test.ts src/lib/db/localRdoService.test.ts src/lib/sync/syncStorage.test.ts src/lib/sync/syncStorage.task3.test.ts src/lib/sync/syncStorage.reidentificacao.test.ts src/lib/sync/pullEventsJanelaCheia.test.ts src/lib/sync/registerDevice.auth.test.ts src/lib/sync/syncTransportSession.test.ts src/lib/sync/guardedSyncTransaction.test.ts src/features/mensagens/mensagensHydration.session.test.ts src/features/mensagens/mensagensRepository.test.ts
npm test -- src/lib/sync/crossNamespaceReconciler.test.ts src/lib/sync/crossNamespaceReconciler.objectUpload.test.ts src/lib/sync/crossNamespaceReconciler.rdoPhoto.test.ts src/lib/sync/syncEngine.session.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/reparoNaoDerrubaOCiclo.test.ts src/lib/sync/syncMemoryOfflineFlow.test.ts src/lib/sync/syncExecutionLease.test.ts src/lib/sync/automaticSyncScheduler.test.ts src/lib/sync/janelaCheiaPedeOutra.test.ts
npm run build
~~~

Expected: PASS; active sync runs first, only one previous database is opened per cycle, all three transports keep their confirmation semantics, no old pull/ack occurs, session/lease loss aborts, and follow-up work is bounded.

- [ ] **Step 8: Commit**

~~~bash
git add apps/web/src/lib/db/cortexNamespaceRegistry.ts apps/web/src/lib/db/cortexNamespaceRegistry.test.ts apps/web/src/lib/db/syncStateRepository.ts apps/web/src/lib/db/syncStateRepository.session.test.ts apps/web/src/lib/db/localRdoService.ts apps/web/src/lib/db/localRdoService.test.ts apps/web/src/lib/sync/crossNamespaceReconciler.ts apps/web/src/lib/sync/crossNamespaceReconciler.test.ts apps/web/src/lib/sync/syncEngine.ts apps/web/src/lib/sync/syncEngine.session.test.ts apps/web/src/lib/sync/syncEngine.auth.test.ts apps/web/src/lib/sync/reparoNaoDerrubaOCiclo.test.ts apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts apps/web/src/lib/sync/guardedSyncTransaction.ts apps/web/src/lib/sync/guardedSyncTransaction.test.ts apps/web/src/lib/sync/syncStorage.ts apps/web/src/lib/sync/syncStorage.test.ts apps/web/src/lib/sync/syncStorage.task3.test.ts apps/web/src/lib/sync/syncStorage.reidentificacao.test.ts apps/web/src/lib/sync/pullEvents.ts apps/web/src/lib/sync/pullEventsJanelaCheia.test.ts apps/web/src/lib/sync/ackCursor.ts apps/web/src/lib/sync/registerDevice.ts apps/web/src/lib/sync/registerDevice.auth.test.ts apps/web/src/lib/sync/syncTransportSession.test.ts apps/web/src/features/mensagens/mensagensHydration.ts apps/web/src/features/mensagens/mensagensHydration.session.test.ts apps/web/src/features/mensagens/mensagensRepository.ts apps/web/src/features/mensagens/mensagensRepository.test.ts apps/web/src/lib/sync/sync.types.ts apps/web/src/lib/sync/automaticSyncScheduler.ts apps/web/src/lib/sync/automaticSyncScheduler.test.ts apps/web/src/lib/sync/useAutomaticSync.ts apps/web/src/lib/sync/janelaCheiaPedeOutra.test.ts
git commit -m "feat(sync): reconcile previous operational namespaces"
~~~

---

### Task 8: Surface Safe Previous-Namespace Status and Prove the Complete Journey

**Files:**
- Create: apps/web/src/lib/sync/crossNamespaceStatus.ts
- Create: apps/web/src/lib/sync/crossNamespaceStatus.test.ts
- Modify: apps/web/src/lib/sync/useSyncStatus.ts:29-45, 256-340
- Modify: apps/web/src/lib/sync/useSyncStatus.test.ts
- Modify: apps/web/src/components/SyncStatusBanner.tsx:246-610
- Modify: apps/web/src/components/SyncStatusBanner.css
- Create: apps/web/src/components/SyncStatusBanner.previousNamespaces.test.tsx
- Modify: apps/web/src/lib/sync/crossNamespaceReconciler.test.ts

**Interfaces:**
- Consumes: same-owner DataNamespaceRecord aggregates and registry change events.
- Produces:

~~~ts
export type PreviousNamespaceStatus = {
  namespaceCount: number;
  pending: {
    syncPush: number;
    objectUpload: number;
    rdoPhoto: number;
  };
  review: number;
  checking: number;
  safeError: string | null;
};

export function loadPreviousNamespaceStatus(
  ownerId: string,
  currentDatabaseName: string,
): Promise<PreviousNamespaceStatus>;
~~~

- [ ] **Step 1: Write privacy and UI tests in RED**

crossNamespaceStatus.test.ts must prove projection sums same-owner, non-current rows and returns no databaseName, ownerId, fingerprint, payload, CPF, session marker, attachment name, or Blob:

~~~ts
expect(await loadPreviousNamespaceStatus(ownerId, currentName)).toEqual({
  namespaceCount: 2,
  pending: { syncPush: 2, objectUpload: 1, rdoPhoto: 1 },
  review: 1,
  checking: 0,
  safeError: "Um escopo anterior precisa de revisão.",
});
~~~

The component test opens the sync popover and expects a section named "Escopos anteriores", class counts, and a live total included in the chip. It must not render "Apagar", "Descartar", raw IDs, or an action that clears an old namespace.

- [ ] **Step 2: Run status tests and record RED**

~~~bash
cd apps/web
npm test -- src/lib/sync/crossNamespaceStatus.test.ts src/lib/sync/useSyncStatus.test.ts src/components/SyncStatusBanner.previousNamespaces.test.tsx
~~~

Expected: FAIL because useSyncStatus reads only getSyncState and listOutboxMutations from the active database.

- [ ] **Step 3: Add the safe projection and registry refresh event**

cortexNamespaceRegistry.ts emits CORTEX_NAMESPACE_REGISTRY_CHANGED_EVENT only after its transaction completes. loadPreviousNamespaceStatus excludes currentDatabaseName, aggregates counts, maps UNKNOWN to checking, and chooses a fixed safe message from a closed set of registry codes. It never returns the stored free-form error or identifiers.

Extend SyncStatusSnapshot with previousNamespaces. useSyncStatus loads the active sync state/outbox and the previous status in parallel after currentDataDatabaseName and getSession().colaboradorId are available. Listen for registry changes alongside connection, visibility, local mutation, and SYNC_COMPLETED_EVENT.

- [ ] **Step 4: Render an accessible, non-destructive previous-scope section**

Add previous pending/review totals to attentionCount and aria-live chipTitle. Inside the existing role=dialog popover, render a labelled section only when namespaceCount or checking is nonzero:

~~~tsx
<section
  className="sync-chip__previous-scopes"
  aria-labelledby="sync-previous-scopes-title"
>
  <h3 id="sync-previous-scopes-title">Escopos anteriores</h3>
  <p>{previousScopeDescription(snapshot.previousNamespaces)}</p>
  {snapshot.previousNamespaces.safeError ? (
    <p className="sync-chip__error">
      {snapshot.previousNamespaces.safeError}
    </p>
  ) : null}
</section>
~~~

The only action remains "Sincronizar agora", which already enters the global owner lock. Do not reuse current handleStartOver, handleDiscardDead, or handleReleaseReview for old databases. Keep focus, Escape, outside-click, responsive width, and existing contrast behavior unchanged.

- [ ] **Step 5: Add the complete IndexedDB journey test**

In crossNamespaceReconciler.test.ts, add one real fake-indexeddb journey:

1. Create namespace A with a generic mutation, OBJECT_UPLOAD plus mensagem_anexos, and a pending RDO photo.
2. Switch the authenticated scope so namespace B becomes current and register both.
3. Run syncNow with successful server mocks.
4. Assert B completes its current push/pull/ack first.
5. Assert A uses an explicit handle and its original device ID.
6. Assert A object and photo confirmations are durable before their counts disappear.
7. Assert A generic APLICADA row is safely pruned, while a REJEITADA row and its safe reason remain visible.
8. Assert no A pull/ack call and no copy in B.
9. Dispatch a new login for the same owner with another Plan 2 sessionMarker during a delayed request and assert the stale run cannot write its result.

- [ ] **Step 6: Run the full frontend verification in GREEN**

~~~bash
cd apps/web
npm test -- src/lib/db/cortexNamespaceRegistry.test.ts src/lib/sync/originDevice.test.ts src/lib/sync/crossNamespaceReconciler.test.ts src/lib/sync/crossNamespaceReconciler.objectUpload.test.ts src/lib/sync/crossNamespaceReconciler.rdoPhoto.test.ts src/lib/sync/crossNamespaceStatus.test.ts src/lib/sync/pushOutbox.test.ts src/features/mensagens/objectUploadSync.test.ts src/features/mensagens/objectUploadSync.session.test.ts src/features/rdos/rdoPhotoSync.test.ts src/lib/sync/syncExecutionLease.test.ts src/lib/sync/syncEngine.session.test.ts src/lib/sync/automaticSyncScheduler.test.ts src/lib/sync/janelaCheiaPedeOutra.test.ts src/lib/sync/useSyncStatus.test.ts src/components/SyncStatusBanner.previousNamespaces.test.tsx
npm test
npm run lint
npm run build
~~~

Expected: all focused tests, the complete Vitest suite, ESLint, TypeScript, Vite/PWA build, and the STAVIA runtime boundary check pass.

- [ ] **Step 7: Commit**

~~~bash
git add apps/web/src/lib/sync/crossNamespaceStatus.ts apps/web/src/lib/sync/crossNamespaceStatus.test.ts apps/web/src/lib/sync/useSyncStatus.ts apps/web/src/lib/sync/useSyncStatus.test.ts apps/web/src/components/SyncStatusBanner.tsx apps/web/src/components/SyncStatusBanner.css apps/web/src/components/SyncStatusBanner.previousNamespaces.test.tsx apps/web/src/lib/sync/crossNamespaceReconciler.test.ts
git commit -m "feat(sync): expose previous-scope reconciliation status"
~~~

---

## Combined Plans 1–3 Verification and Release Gate

Do not start this gate until every task in plans 1, 2, and 3 is committed on the same branch and git status contains no unrelated change.

- [ ] **Step 1: Verify plan boundaries and repository cleanliness**

~~~bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git diff --check origin/develop...HEAD
git diff --check
git status --short
if rg -n "getCortexDb\\(" apps/web/src/lib/sync/crossNamespaceReconciler.ts apps/web/src/lib/sync/originDevice.ts; then
  echo "Old-namespace code resolved the active database implicitly." >&2
  exit 1
fi
if rg -n "pullEvents|acknowledgeCurrentCursor|grant_capsules|grant_capsule_keys|grant_capsule_state|withGrantCapsuleLock|ownerLookup|sessionLookup" apps/web/src/lib/sync/crossNamespaceReconciler.ts apps/web/src/lib/sync/originDevice.ts apps/web/src/lib/db/cortexNamespaceRegistry.ts; then
  echo "A forbidden pull/ack or auth-capsule dependency crossed into old-namespace reconciliation." >&2
  exit 1
fi
~~~

Expected: diff check is clean; status contains only intended plan implementation if not yet committed; both source searches return no matches. getCortexDb may remain only in active-path compatibility wrappers outside these old-namespace modules.

- [ ] **Step 2: Run the complete frontend and backend gates from the combined revision**

~~~bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/web"
npm test
npm run lint
npm run build
cd "$REPO_ROOT/apps/api"
./mvnw -q test
./mvnw -q -Ppostgresql-it verify
./mvnw -q -Pmysql-it verify
cd "$REPO_ROOT"
bash scripts/security/scan-cortex-secrets.sh
bash scripts/security/test-neon-migration-contract.sh
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-local-compose-security.sh
bash scripts/security/test-normal-runtime-launchers.sh
bash scripts/security/test-production-workflow-contract.sh
bash scripts/security/test-production-workflow-contract-regressions.sh
bash scripts/deploy/test-trigger-and-wait-render.sh
bash scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh
bash scripts/security/test-production-publication.sh
git diff --check origin/develop...HEAD
git diff --check
~~~

Expected: frontend unit/IndexedDB/PWA checks pass; backend unit, PostgreSQL 18, and MySQL 8.4 integration checks from plan 1 pass with no selected IT skipped; Plan 1 migration/secret gates and every Plan 2 keyring, Compose, launcher, workflow, Render-wrapper, Cloudflare-wrapper, and publication contract pass again on this same combined SHA; no whitespace error exists. Record these as local evidence only, not deployment evidence.

- [ ] **Step 3: Merge only after reviewing the combined diff**

~~~bash
git log --oneline --decorate -12
git diff --stat origin/develop...HEAD
git diff --name-status origin/develop...HEAD
~~~

Expected: the combined diff contains plan 1 backend auth/session work, plan 2 capsule PWA work, and plan 3 cross-namespace sync work; it contains no secret values, generated build output, or unrelated Claude/Codex edits.

- [ ] **Step 4: Verify CI and the exact production revision after authorized merge/push**

~~~bash
git fetch origin
git rev-parse origin/develop
gh run list --workflow "Córtex CI" --branch develop --limit 10 --json databaseId,headSha,status,conclusion,url
gh run list --workflow "Córtex production" --branch develop --limit 10 --json databaseId,headSha,status,conclusion,url
curl --fail --silent --show-error https://cortex-api-4038.onrender.com/api/health
~~~

Expected: Córtex CI and the production job named Release exact production revision conclude success for the exact origin/develop SHA; Render /api/health reports that same revision; Cloudflare Pages verification in the production job reports the same revision. A green preliminary gate is not sufficient.

- [ ] **Step 5: Perform authenticated browser acceptance on the exact served revision**

Use a test owner with two controlled worksite scopes and one test device:

1. Log in online and verify the plan 2 capsule is encrypted and no password/raw grant appears in persistence.
2. In scope A, create one generic offline RDO mutation, one message attachment OBJECT_UPLOAD, and one RDO photo.
3. Change to scope B so another operational database becomes current; confirm the old namespace appears under "Escopos anteriores".
4. Reconnect and press "Sincronizar agora".
5. Confirm B runs its normal current push/pull/ack first.
6. Confirm A object upload, photo upload/link, and generic push complete in the mandated order using the original device ID.
7. Confirm A disappears from pending counts only after each transport's own durable confirmation; no data is copied into B.
8. Force one conflict/rejection and confirm it remains visible with a safe reason; confirm there is no destructive old-namespace reset.
9. Log out or replace the same-owner session during a delayed replay and confirm the stale run stops without consuming the remaining queue.
10. Verify server-side RDO acceptance, immutable revenue evidence, and PDOR recalculation separately; do not infer them from an empty transport outbox.
11. Cut the network again, reload the PWA, and verify plan 2 offline unlock still works and plan 3 did not touch cortex-auth-vaults.
12. Complete Plan 2's post-release Edge/Chrome feature-probe runbook on this same served SHA: persisted non-extractable CryptoKeys, full-browser restart without a renewal prompt, password and passkey/PRF profiles, a sub-six-hour no-POST trace, a separately prepared over-six-real-hour single-renewal trace, and a two-tab one-POST proof.
13. Confirm the signed TTL is at most seven days and report any real seven-day soak as a separate ongoing observation; never substitute a fake-clock test for elapsed browser time.
14. Revisit scope A after it has reconciled to zero, create another offline mutation there, return to B, and confirm the new A work is rediscovered and replayed; the earlier SCANNED/zero registry snapshot must not hide it.

Record browser acceptance, capsule/network observations, API/server evidence, and PDOR evidence separately. This evidence is not added to the candidate commit it verifies. If it is committed later, the documentation-only commit must name the earlier deployed SHA and must not be presented as evidence for itself. If any one of the three transport classes is absent from the controlled journey, report that class as unproven rather than declaring the migration complete.

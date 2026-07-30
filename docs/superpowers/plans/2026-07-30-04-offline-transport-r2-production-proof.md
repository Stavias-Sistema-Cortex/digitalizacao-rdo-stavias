# Offline Transport and R2 Production Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make pending authorship and sync durable across worksite-scope changes, persist an RDO and its real attachments atomically, enforce purpose-specific object authorization and integrity, and record a PII-free production proof of the complete offline and R2 paths.

**Architecture:** Keep hydrated, permission-sensitive caches in the existing scope-hashed `cortex-data-v1-*` database, but move locally authored records, blobs, ontology events, cursors, and outbox mutations into forward-only IndexedDB partitions keyed only by owner and device. Sync iterates those partitions automatically and treats each mutation independently. On the server, PostgreSQL V66 and MySQL V46 evolve the shared stored-object repository in parity, classify legacy bindings without guessing, and feed one purpose-policy registry for every upload/read/delete; downloads are staged and SHA-256-verified before HTTP 200.

**Tech Stack:** React 19, TypeScript 6, Vite 8, Vitest 4, `idb` 8, Spring Boot 3, Java 21, JDBC, Flyway, PostgreSQL 18/Testcontainers, AWS SDK S3-compatible storage (Cloudflare R2), Bash, `curl`, `jq`, `sha256sum`/`shasum`.

## Global Constraints

- Complete plans 01, 02, and 03 first. Plan 03 must land before this plan because RDO attachment removal consumes the same authoritative ontology/version contract and the Obras lifecycle behavior must already be stable.
- The release order is strict: finish and commit this plan's implementation and acceptance harness, execute Plan 05, publish one immutable SHA containing Plans 01–05, and only then execute Task 8 against that exact SHA. The official offline/R2 proof and the official visual proof must name the same deployed SHA.
- PostgreSQL changes are forward-only in `apps/api/src/main/resources/db/migration-postgresql/V66__rdo_object_transport_integrity.sql`; never edit V44 or an applied migration.
- Because `JdbcStoredObjectRepository` is shared and unprofiled, add the equivalent forward-only MySQL migration `apps/api/src/main/resources/db/migration/V46__rdo_object_transport_integrity.sql` after Plan 01's V45; never alter V29–V45.
- Raise the exact required PostgreSQL schema from `65` to `66` in Java, YAML, and every exact-version test.
- Use Java 21 for every Maven command: `cortex_java21="$(/usr/libexec/java_home -v 21)"; JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH"`.
- A transport partition is identified by `(ownerId, deviceId)`; `papelAcesso`, `escopoGlobal`, and `obraIds` never enter its name or lookup key.
- Logout, expiry, and a different `colaboradorId` stop an in-flight sync; a role/worksite-scope refresh for the same online session does not.
- Never delete or overwrite a legacy IndexedDB before every copied mutation has a canonical server acknowledgement; a divergent duplicate is quarantined for review.
- RDO save is one IndexedDB transaction containing draft, children, attachment blobs, upload mutations, RDO mutation, and local ontology events.
- Canonical payloads are immutable. Upload completion creates a causally linked replacement envelope; it never edits the hash-bearing original envelope.
- Upload idempotency is scoped by `(purpose, domainReference, actorId, clientUploadId)` and backed by an immutable receipt plus active-object uniqueness. An identical replay returns the original object/result; divergent bytes or canonical metadata return a stable conflict and never overwrite the receipt, metadata, or R2 object.
- RDO attachment removal is a domain mutation: actor/name/device and applied time come from the validated server context, `clientMutationId` and payload hash are stable, base/result versions are persisted, and replay returns the original result/event without creating a second event or deletion transition.
- RDO object access needs any valid authenticated session; Mensagens still needs current conversation participation; Financeiro keeps its existing guard; `LEGADO` is denied.
- Legacy backfill may classify a stored object only when the set of distinct domain/reference candidates has exactly one member. Zero-candidate rows become `LEGADO`; ambiguous multi-domain or multi-reference rows become `LEGADO` and, when not already archived, `QUARENTENA`. Never choose a purpose by join order or overwrite one legacy relation with another.
- No generic owner/worksite fallback is allowed after purpose resolution.
- A download cannot emit response bytes until size, media type, and persisted SHA-256 are verified.
- `/api/readiness` remains a technical `put/get/delete` storage probe. It is not acceptance evidence for RDO upload, authorization, replay, or download integrity.
- Evidence, logs, fixtures, screenshots, and committed artifacts contain no CPF, e-mail, person name, cookie, CSRF token, database URL, R2 credential, or raw attachment.
- R2 absence is checked inside the deployed API runtime using its existing storage credentials. Browser-side and orchestration scripts receive only a sanitized server proof; they never receive an R2 key, access key, secret, endpoint credential, or arbitrary-key probe.
- Task 8 is a post-Plan-05, post-publication gate only. It makes no source change and creates no commit; if any gate fails, fix the owning task, publish a new SHA, and restart all acceptance evidence for that new SHA.

---

## File Map

| Responsibility | Files |
| --- | --- |
| Stable owner/device IndexedDB and connection lifecycle | Create `apps/web/src/lib/db/authoringDb.ts`, `apps/web/src/lib/db/authoringDb.test.ts`; modify `apps/web/src/features/auth/authSession.ts`, `apps/web/src/lib/db/localDataNamespace.ts`, `apps/web/src/lib/db/cortexDb.ts`, `apps/web/src/lib/sync/syncSession.ts` |
| Forward-only legacy bridge | Create `apps/web/src/lib/db/legacyAuthoringMigration.ts`, `apps/web/src/lib/db/legacyAuthoringMigration.test.ts`; modify `apps/web/src/App.tsx`, `apps/web/src/bootstrap/normalBootstrap.tsx` |
| Transport cutover and automatic per-device replay | Modify `apps/web/src/lib/sync/localMutationCoordinator.ts`, `syncEngine.ts`, `pushOutbox.ts`, `syncStorage.ts`, `syncExecutionLease.ts`, `sync.types.ts`, and the repositories for outbox, sync state, processed events, and operational events |
| Atomic RDO photos and generic uploads | Create `apps/web/src/lib/sync/objectUploadSync.ts`, `apps/web/src/lib/sync/objectUploadSync.test.ts`, `apps/web/src/lib/db/localRdoAttachmentAtomicity.test.ts`; modify `db.types.ts`, `localRdoService.ts`, `rdoAttachmentRepository.ts`, `rdoPhotoService.ts`, `RdoCreatePage.tsx`, and the existing Mensagens upload module/tests |
| PostgreSQL V66/MySQL V46 object model | Create both migrations, immutable upload-receipt record, `apps/api/src/test/java/com/projeto/cortex/storage/PostgresqlStoredObjectLifecycleIT.java`, `apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectLifecycleMysqlMigrationTest.java`, and `apps/api/src/test/java/com/projeto/cortex/pdor/StoredObjectLifecycleMysqlIntegrationTest.java`; modify schema-version constants/config/tests, stored-object records/repository, and RDO attachment request/persistence |
| Domain authorization and verified download | Create `StoredObjectPurpose.java`, `StoredObjectAccessPolicy.java`, `StoredObjectPolicyRegistry.java`, `StoredObjectIntegrityVerifier.java`, `RdoStoredObjectAccessPolicy.java`, `RdoAttachmentController.java`, and `MessageStoredObjectAccessPolicy.java`; adapt Financeiro and generic object services/controllers |
| Asynchronous removal and ontology | Create `StoredObjectDeletionService.java`, `StoredObjectDeletionScheduler.java`, `RdoAttachmentRemovalRequest.java`, and their tests; modify repository, RDO attachment service/controller, authoritative RDO trace/event services, web removal mutation, and S3 tests |
| Runtime acceptance harness | Create a server-side `StorageAcceptanceProofRunner`, its tests, `docs/runbooks/cortex-offline-r2-acceptance.md`, `scripts/qa/verify-offline-r2-evidence.sh`, and its contract test; invoke the runner directly from the JAR already present in the deployed image and modify `docs/production-runbook.md` |

### Task 1: Stable Authoring Partition and Transport Session Boundary

**Files:**
- Create: `apps/web/src/lib/db/authoringDb.ts`
- Create: `apps/web/src/lib/db/authoringDb.test.ts`
- Modify: `apps/web/src/features/auth/authSession.ts`
- Modify: `apps/web/src/features/auth/authSession.test.ts`
- Modify: `apps/web/src/lib/db/localDataNamespace.ts`
- Modify: `apps/web/src/lib/db/localDataNamespace.test.ts`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Modify: `apps/web/src/lib/sync/syncSession.ts`
- Modify: `apps/web/src/lib/sync/syncTransportSession.test.ts`

**Interfaces:**
- Produces: `AuthoringPartition { ownerId: string; deviceId: string; databaseName: string }`, `authoringDatabaseName(ownerId, deviceId)`, `currentAuthoringPartition()`, `listAuthoringPartitions(ownerId)`, `getAuthoringDb(partition?)`, and `closeAuthoringDb()`.
- Produces: `SyncSessionGuard { fingerprint: string; userId: string; sessionExpiresAt: string }`; its fingerprint is derived from `colaboradorId + expiraEm`, never from authorization scope.
- Preserves: `databaseNameForScope(ownerId, scopeMaterial)` for hydrated caches only.

- [ ] **Step 1: Write the failing owner/device and session-boundary tests**

```ts
expect(authoringDatabaseName(OWNER, DEVICE)).toBe(
  `cortex-authoring-v1-${OWNER}-${DEVICE}`,
);
expect(await databaseNameForScope(OWNER, "BETA:obra-a")).not.toBe(
  await databaseNameForScope(OWNER, "BETA:obra-b"),
);
const guard = captureOnlineSyncSession();
setSession(profileForWorksite(WORKSITE_B));
expect(() => assertSyncSession(guard)).not.toThrow();
clearSession();
expect(() => assertSyncSession(guard)).toThrow(/sessão mudou/i);
```

- [ ] **Step 2: Run the RED gate**

Run: `cd apps/web && npm test -- src/lib/db/authoringDb.test.ts src/lib/db/localDataNamespace.test.ts src/lib/sync/syncTransportSession.test.ts src/features/auth/authSession.test.ts`

Expected: FAIL because `authoringDb.ts` does not exist and the current sync fingerprint includes role and `obraIds`.

- [ ] **Step 3: Implement the stable registry, schema, and guard**

```ts
export function authoringDatabaseName(ownerId: string, deviceId: string): string {
  requireUuid(ownerId, "ownerId");
  requireUuid(deviceId, "deviceId");
  return `cortex-authoring-v1-${ownerId}-${deviceId}`;
}

export function syncSessionFingerprint(session: AuthProfile): string {
  return canonicalMutationJson({
    colaboradorId: session.colaboradorId,
    expiraEm: session.expiraEm,
  });
}
```

Use an IndexedDB registry named `cortex-authoring-registry-v1`, keyed by `ownerId`, to persist the active device UUID and all discovered legacy device partitions. `AuthoringDbSchema` contains local-authoring stores (`rdos`, RDO children, `rdo_attachments`, `mensagens`, `mensagem_anexos`, `tarefas`, `teams`, `team_worksites`, `obras`, `service_catalog`, `service_price_versions`) plus `outbox_mutations`, `operational_events`, `processed_events`, and `sync_state`. Keep remote hydrated caches in `CortexDbSchema`. Close authoring connections only when the owner changes, logs out, or expires; a scope-only auth event closes only the cache connection.

- [ ] **Step 4: Run the GREEN gate**

Run: `cd apps/web && npm test -- src/lib/db/authoringDb.test.ts src/lib/db/localDataNamespace.test.ts src/lib/sync/syncTransportSession.test.ts src/features/auth/authSession.test.ts`

Expected: PASS; the authoring name is stable across scope changes, cache names remain isolated, logout/user replacement invalidates the guard, and a same-session scope refresh does not.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/lib/db/authoringDb.ts apps/web/src/lib/db/authoringDb.test.ts apps/web/src/features/auth/authSession.ts apps/web/src/features/auth/authSession.test.ts apps/web/src/lib/db/localDataNamespace.ts apps/web/src/lib/db/localDataNamespace.test.ts apps/web/src/lib/db/cortexDb.ts apps/web/src/lib/sync/syncSession.ts apps/web/src/lib/sync/syncTransportSession.test.ts
git commit -m "feat(web): partition local authorship by owner and device"
```

### Task 2: Forward-Only Legacy IndexedDB Bridge

**Files:**
- Create: `apps/web/src/lib/db/legacyAuthoringMigration.ts`
- Create: `apps/web/src/lib/db/legacyAuthoringMigration.test.ts`
- Modify (created in Task 1): `apps/web/src/lib/db/authoringDb.ts`
- Modify: `apps/web/src/App.tsx`
- Modify: `apps/web/src/bootstrap/normalBootstrap.tsx`

**Interfaces:**
- Consumes: Task 1 `AuthoringPartition` and registry.
- Produces: `migrateLegacyAuthoring(ownerId): Promise<LegacyMigrationReport>` with status `COMPLETE | BLOCKED_UNSUPPORTED | QUARANTINED`.
- Produces: `listLegacyBridgeSources(ownerId, deviceId)` and `markLegacySourceAcked(sourceName, clientMutationId, commitSeq)`.

- [ ] **Step 1: Write the failing bridge tests**

```ts
const report = await migrateLegacyAuthoring(OWNER);
expect(report.copiedMutationIds).toEqual(["mutation-a"]);
expect(report.coalescedMutationIds).toEqual(["mutation-a"]);
expect(report.quarantinedMutationIds).toEqual(["mutation-divergent"]);
expect(report.initialPullCursorByDevice[DEVICE]).toBe(LOWEST_LEGACY_CURSOR);
expect((await indexedDB.databases()).map((entry) => entry.name)).toContain(legacyName);
expect(await listLegacyBridgeSources(OWNER, DEVICE)).toContainEqual(
  expect.objectContaining({ sourceName: legacyName, state: "AWAITING_ACK" }),
);
```

Also stub an environment without `indexedDB.databases`; assert `BLOCKED_UNSUPPORTED` is persisted and surfaced rather than returning an empty queue.

- [ ] **Step 2: Run the RED gate**

Run: `cd apps/web && npm test -- src/lib/db/legacyAuthoringMigration.test.ts`

Expected: FAIL because the bridge module and migration journal do not exist.

- [ ] **Step 3: Implement deterministic copy, coalescing, and quarantine**

Enumerate only names matching `cortex-data-v1-${ownerId}-*`. Copy every non-`SYNCED` mutation and its closure: principal record, RDO children, local event, blob/attachment, upload mutation and causal dependencies. Group by the immutable mutation `deviceId`, falling back only to that source database’s `sync_state.deviceId`. For each target partition, initialize pull from the **lowest** valid acknowledged cursor among its legacy sources; if any source has no trustworthy cursor, start from zero. Never take the maximum. Deduplicate replayed pull events by authoritative event/commit ID so the conservative cursor cannot duplicate local projections. Verify `userId === ownerId`, canonical payload hash, and `(ownerId, deviceId, clientMutationId)`. Coalesce byte-identical rows; store both digests and source names in `authoring_quarantine` when the same key diverges. Keep a dual-read journal until the server ACK is stored. Do not call `deleteDB` from migration code.

- [ ] **Step 4: Run the GREEN gate**

Run: `cd apps/web && npm test -- src/lib/db/legacyAuthoringMigration.test.ts src/lib/db/authoringDb.test.ts src/App.onlineHandoff.test.tsx`

Expected: PASS, including old-database preservation, unsupported-enumeration visibility, lowest-safe cursor selection, pull dedupe, mutation dedupe, and divergent-row quarantine.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/lib/db/legacyAuthoringMigration.ts apps/web/src/lib/db/legacyAuthoringMigration.test.ts apps/web/src/lib/db/authoringDb.ts apps/web/src/App.tsx apps/web/src/bootstrap/normalBootstrap.tsx
git commit -m "feat(web): bridge legacy scoped outboxes without data loss"
```

### Task 3: Cut Transport Over and Replay Automatically Per Device

**Files:**
- Modify: `apps/web/src/lib/sync/localMutationCoordinator.ts`
- Modify: `apps/web/src/lib/sync/syncEngine.ts`
- Modify: `apps/web/src/lib/sync/pushOutbox.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.ts`
- Modify: `apps/web/src/lib/sync/syncExecutionLease.ts`
- Modify: `apps/web/src/lib/sync/sync.types.ts`
- Modify: `apps/web/src/lib/db/outboxRepository.ts`
- Modify: `apps/web/src/lib/db/syncStateRepository.ts`
- Modify: `apps/web/src/lib/db/processedEventRepository.ts`
- Modify: `apps/web/src/lib/db/operationalEventRepository.ts`
- Modify: `apps/web/src/lib/sync/pushOutbox.test.ts`
- Modify: `apps/web/src/lib/sync/syncEngine.auth.test.ts`
- Modify: `apps/web/src/lib/sync/syncExecutionLease.test.ts`

**Interfaces:**
- Produces: `syncPartition(partition, guard): Promise<PartitionSyncSummary>`.
- Changes: `SyncRunSummary` adds `partitions` and `items: SyncItemResult[]`; one rejected/conflicted item does not reject the run promise.

- [ ] **Step 1: Write failing grouping, scope-change, and item-isolation assertions**

```ts
await syncNow();
expect(mocks.pushApi.mock.calls.map(([body]) => body.dispositivoId)).toEqual([
  DEVICE_A,
  DEVICE_B,
]);
expect(mocks.pushApi.mock.calls[0][0].mutacoes).toHaveLength(1);
expect(summary.items).toEqual(expect.arrayContaining([
  expect.objectContaining({ clientMutationId: "ok", status: "APLICADA" }),
  expect.objectContaining({ clientMutationId: "bad", status: "REJEITADA" }),
]));
expect(summary.applied).toBe(1);
```

During the mocked request, refresh the same user from worksite A to B and assert completion; repeat with another user and assert no ACK or local result is written.

- [ ] **Step 2: Run the RED gate**

Run: `cd apps/web && npm test -- src/lib/sync/pushOutbox.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/syncExecutionLease.test.ts src/lib/sync/syncTransportSession.test.ts`

Expected: FAIL because repositories still open the scope-hashed database and `syncNow` assumes one current device.

- [ ] **Step 3: Implement partition iteration and per-item completion**

Change transport repositories and guarded transactions to accept an `AuthoringPartition`. `syncNow` captures one owner/session guard, enumerates that owner’s partitions, registers each immutable device ID, processes uploads, pushes, pulls, and ACKs inside that partition, then aggregates results. Convert server mutation results into `SyncItemResult` without throwing for `REJEITADA`, `CONFLITO`, or a stable payload error; throw only for transport/session/integrity failures. Dispatch the existing local-write event after the authoring transaction commits, so startup, local write, `online`, foreground, interval, and bounded backoff remain automatic.

- [ ] **Step 4: Run the GREEN gate**

Run: `cd apps/web && npm test -- src/App.automaticSync.test.tsx src/lib/sync/automaticSyncRetryStorage.test.ts src/lib/sync/automaticSyncScheduler.test.ts src/lib/sync/pushOutbox.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/syncEngine.session.test.ts src/lib/sync/syncExecutionLease.test.ts src/lib/sync/syncTransportSession.test.ts`

Expected: PASS; no worksite-scope value selects the outbox, and one item error does not stop later items or other device partitions.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/lib/sync/localMutationCoordinator.ts apps/web/src/lib/sync/syncEngine.ts apps/web/src/lib/sync/pushOutbox.ts apps/web/src/lib/sync/syncStorage.ts apps/web/src/lib/sync/syncExecutionLease.ts apps/web/src/lib/sync/sync.types.ts apps/web/src/lib/db/outboxRepository.ts apps/web/src/lib/db/syncStateRepository.ts apps/web/src/lib/db/processedEventRepository.ts apps/web/src/lib/db/operationalEventRepository.ts apps/web/src/lib/sync/pushOutbox.test.ts apps/web/src/lib/sync/syncEngine.auth.test.ts apps/web/src/lib/sync/syncExecutionLease.test.ts
git commit -m "feat(web): replay authoring partitions independently"
```

### Task 4: Save RDO, Photos, Events, Uploads, and Outbox Atomically

**Files:**
- Create: `apps/web/src/lib/db/localRdoAttachmentAtomicity.test.ts`
- Create: `apps/web/src/lib/sync/objectUploadSync.ts`
- Create: `apps/web/src/lib/sync/objectUploadSync.test.ts`
- Modify: `apps/web/src/lib/db/db.types.ts`
- Modify: `apps/web/src/lib/db/localRdoService.ts`
- Modify: `apps/web/src/lib/db/rdoRepository.ts`
- Modify: `apps/web/src/lib/db/rdoAttachmentRepository.ts`
- Modify: `apps/web/src/features/rdos/rdoPhotoService.ts`
- Modify: `apps/web/src/features/rdos/rdoPhotoMutationContract.test.ts`
- Modify: `apps/web/src/features/rdos/RdoCreatePage.tsx`
- Modify: `apps/web/src/features/mensagens/objectUploadSync.ts`
- Modify: `apps/web/src/features/mensagens/objectUploadSync.test.ts`
- Modify: `apps/web/src/features/mensagens/objectUploadSync.session.test.ts`

**Interfaces:**
- Extends: `RdoAttachmentRecord` with `objectId: string | null`, `uploadMutationId: string`, and `sha256: string`; the Blob remains local-only.
- Produces: `processObjectUploads(limit, partition, guard, adapters)` and `ObjectUploadAdapter`.
- Produces: immutable upload replacement with `causationId` equal to the blocked original RDO mutation ID.

- [ ] **Step 1: Write the failing atomicity and immutable-replacement tests**

```ts
const before = await authoringCounts(db);
await seedDuplicateUploadMutation(db, draftWithPhoto.attachments[0].uploadMutationId);
await expect(saveRdoDraftAtomically(draftWithPhoto)).rejects.toThrow(/já existe/i);
expect(await authoringCounts(db)).toEqual({ ...before, outbox: before.outbox + 1 });
expect(replacement.causationId).toBe(original.clientMutationId);
expect(original.payloadHash).toBe(originalHash);
```

- [ ] **Step 2: Run the RED gate**

Run: `cd apps/web && npm test -- src/lib/db/localRdoAttachmentAtomicity.test.ts src/features/rdos/rdoPhotoMutationContract.test.ts src/lib/sync/objectUploadSync.test.ts`

Expected: FAIL because photos are written before RDO save, have no SHA/upload ID, and the uploader is Mensagens-specific.

- [ ] **Step 3: Implement one authoring transaction and adapter-based uploads**

Compute SHA-256 after compression in `processRdoPhoto`, retain the Blob in the draft until save, and have `saveRdoDraftAtomically` add the RDO, children, attachments, `OBJECT_UPLOAD` rows, RDO mutation, and photo/RDO ontology events in one transaction. Each RDO mutation depends on all active upload mutation IDs and begins blocked with `OBJECT_UPLOAD_REQUIRED`. Move the shared upload loop to `lib/sync/objectUploadSync.ts`; the RDO adapter builds `/api/rdos/${rdoId}/anexos/uploads?obraId=${encodeURIComponent(obraId)}&clientUploadId=${encodeURIComponent(clientUploadId)}`, while the Mensagens adapter retains its conversation reference. After all uploads verify returned size/hash, create a new canonical RDO envelope containing `{ objectId, sha256 }`, reject the original as superseded, and preserve its bytes/hash.

- [ ] **Step 4: Run the GREEN gate**

Run: `cd apps/web && npm test -- src/lib/db/localRdoAttachmentAtomicity.test.ts src/lib/db/localRdoService.test.ts src/lib/db/localRdoSyncingRace.test.ts src/features/rdos/rdoPhotoMutationContract.test.ts src/lib/sync/objectUploadSync.test.ts src/features/mensagens/objectUploadSync.test.ts src/features/mensagens/objectUploadSync.session.test.ts`

Expected: PASS; an injected abort leaves no partial record, RDO and Mensagens uploads use adapters, and canonical originals remain immutable.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/lib/db/localRdoAttachmentAtomicity.test.ts apps/web/src/lib/sync/objectUploadSync.ts apps/web/src/lib/sync/objectUploadSync.test.ts apps/web/src/lib/db/db.types.ts apps/web/src/lib/db/localRdoService.ts apps/web/src/lib/db/rdoRepository.ts apps/web/src/lib/db/rdoAttachmentRepository.ts apps/web/src/features/rdos/rdoPhotoService.ts apps/web/src/features/rdos/rdoPhotoMutationContract.test.ts apps/web/src/features/rdos/RdoCreatePage.tsx apps/web/src/features/mensagens/objectUploadSync.ts apps/web/src/features/mensagens/objectUploadSync.test.ts apps/web/src/features/mensagens/objectUploadSync.session.test.ts
git commit -m "feat(rdo): persist attachments and upload intent atomically"
```

### Task 5: Add PostgreSQL V66 and MySQL V46 Object Purpose, Binding, Integrity, and Retry State

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V66__rdo_object_transport_integrity.sql`
- Create: `apps/api/src/main/resources/db/migration/V46__rdo_object_transport_integrity.sql`
- Create: `apps/api/src/test/java/com/projeto/cortex/storage/PostgresqlStoredObjectLifecycleIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectLifecycleMysqlMigrationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/StoredObjectLifecycleMysqlIntegrationTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java`
- Modify: `apps/api/src/main/resources/application-postgresql-common.yml`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectRecord.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectUploadReceipt.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/JdbcStoredObjectRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoCreateRequest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationReadinessTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlEffectiveConfigurationTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlFoundationContractTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlProfileModesContractTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuardTest.java`
- Modify (updated in Plan 01): `apps/api/src/test/java/com/projeto/cortex/migration/MigrationVersionUniquenessTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlReleaseMarkerIT.java`

**Interfaces:**
- Produces columns `stored_object.proposito`, `referencia_dominio_id`, `client_upload_id`, integrity timestamps/status/error, deletion retry fields, and `rdo_attachment.stored_object_id`/`sha256`.
- Produces active-only dedupe uniqueness for `TEMPORARIO`/`DISPONIVEL`, permitting a fresh retry after `QUARENTENA` or `ARQUIVADO`: a PostgreSQL partial unique index and a MySQL unique index over nullable generated column `active_dedupe_key`.
- Produces immutable `stored_object_upload_receipt(proposito, referencia_dominio_id, ator_id, client_upload_id, stored_object_id, upload_payload_hash, sha256, tamanho_bytes, metadata_hash, criado_em)` keyed by `(proposito, referencia_dominio_id, ator_id, client_upload_id)`.
- Produces active upload uniqueness on `(proposito, referencia_dominio_id, criado_por, client_upload_id)`: a PostgreSQL partial unique index and MySQL composite unique using nullable generated `active_client_upload_id`.
- Produces retry-safe upload claim fields `upload_lease_token`, `upload_lease_expira_em`, and `upload_tentativas`; an expired claim resumes the same receipt/object/storage key after a crash.
- Produces `UNIQUE (id, obra_id, sha256)` and the matching `rdo_attachment(stored_object_id, obra_id, sha256)` composite FK in both databases.
- Maps every new absolute time to Java `Instant`; MySQL persists the same UTC instants as `DATETIME(6)`, never server-local `LocalDateTime`.

- [ ] **Step 1: Write the failing PostgreSQL and MySQL lifecycle tests**

```java
assertThat(columns("stored_object")).contains(
        "proposito", "referencia_dominio_id", "client_upload_id",
        "integridade_status", "exclusao_solicitada_em",
        "exclusao_tentativas", "proxima_tentativa_exclusao_em",
        "upload_lease_token", "upload_lease_expira_em", "upload_tentativas"
);
assertThat(columns("rdo_attachment")).contains("stored_object_id", "sha256");
assertThat(hasCompositeForeignKey(
        "rdo_attachment",
        List.of("stored_object_id", "obra_id", "sha256"),
        "stored_object",
        List.of("id", "obra_id", "sha256"))).isTrue();
assertThat(canReserveSameDedupeAfterQuarantine()).isTrue();
assertThat(hasUploadReceiptPrimaryKey(
        "proposito", "referencia_dominio_id", "ator_id",
        "client_upload_id")).isTrue();
assertThat(hasActiveUploadIdentityUnique()).isTrue();
assertThat(PostgresqlSchemaVersion.REQUIRED).isEqualTo("66");
```

In `StoredObjectLifecycleMysqlMigrationTest`, read V46 and assert it declares generated nullable `active_dedupe_key` and `active_client_upload_id`, `DATETIME(6)` lifecycle/receipt fields, both active uniques, the immutable receipt PK, `UNIQUE (id, obra_id, sha256)`, and the three-column RDO attachment FK. In `StoredObjectLifecycleMysqlIntegrationTest`, migrate a real MySQL database from the existing chain, then assert:

```java
assertThat(generatedColumnExpression("stored_object", "active_dedupe_key"))
        .contains("TEMPORARIO").contains("DISPONIVEL");
assertThat(generatedColumnExpression(
        "stored_object", "active_client_upload_id"))
        .contains("client_upload_id");
assertThat(canReserveSameDedupeAfterQuarantine()).isTrue();
assertThat(canInsertOnlyOneUploadReceiptPerIdentity()).isTrue();
assertThat(concurrentReceiptInsertCount()).isEqualTo(1);
assertThat(concurrentStoredObjectCount()).isEqualTo(1);
assertThat(replaySameUploadIdentity())
        .isEqualTo(firstReservedObjectId());
assertThat(reserveDivergentUploadIdentity())
        .isEqualTo("CONFLICT");
assertThatThrownBy(this::bindRdoAttachmentWithDifferentWorksiteOrHash)
        .hasRootCauseInstanceOf(SQLException.class);
assertThat(classifySingleLegacyBinding()).isEqualTo("MENSAGEM");
assertThat(classifyAmbiguousLegacyBinding())
        .containsExactly("LEGADO", "QUARENTENA");
```

- [ ] **Step 2: Run the hermetic RED gate**

Run:

```bash
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
  ./mvnw -q -Dtest='PostgresqlStoredObjectLifecycleIT,StoredObjectLifecycleMysqlMigrationTest,PostgresqlFoundationContractTest,PostgresqlSchemaReadinessGuardTest,MigrationVersionUniquenessTest' test
```

Expected: FAIL because V66/V46 and their columns do not exist and the required PostgreSQL version is still 65 after Plan 01.

- [ ] **Step 3: Run the real-MySQL RED gate**

Run:

```bash
cd apps/api
test -n "${CORTEX_MYSQL_ROOT_PASSWORD:-}" || {
  echo "CORTEX_MYSQL_ROOT_PASSWORD is required for the MySQL parity gate" >&2
  exit 1
}
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
  ./mvnw -q -Dtest='StoredObjectLifecycleMysqlIntegrationTest' test
```

Expected: FAIL because a real MySQL migration has no V46 lifecycle model. Do not treat an environment-disabled/skipped test as evidence.

- [ ] **Step 4: Implement deterministic V66/V46 parity and exact PostgreSQL version updates**

In V66, drop `stored_object_dedupe_key_key`, add purpose/reference/idempotency/integrity/deletion fields, replace `chk_stored_object_status` to include `EXCLUSAO_PENDENTE`, and create `uq_stored_object_active_dedupe` with `WHERE status IN ('TEMPORARIO','DISPONIVEL')`. Add `UNIQUE (id, obra_id, sha256)` on `stored_object` and the exact composite FK `rdo_attachment(stored_object_id, obra_id, sha256) → stored_object(id, obra_id, sha256)`, so neither another worksite nor different bytes can be rebound by ID.

Create `stored_object_upload_receipt` in both migrations with the immutable identity PK `(proposito, referencia_dominio_id, ator_id, client_upload_id)`, an FK to `stored_object`, canonical upload/metadata hashes, content SHA, byte size, and creation instant. Add a PostgreSQL partial unique over active stored objects on `(proposito, referencia_dominio_id, criado_por, client_upload_id)` where the upload ID is non-null and status is `TEMPORARIO`/`DISPONIVEL`. Add bounded upload lease token/expiry/attempt fields so a crash can resume the same reserved object without allocating a new key.

V46 implements the same logical model for the shared MySQL runtime. Drop `uq_stored_object_dedupe`, add `active_dedupe_key CHAR(64) ... GENERATED ALWAYS AS (CASE WHEN status IN ('TEMPORARIO','DISPONIVEL') THEN dedupe_key ELSE NULL END) STORED` and `active_client_upload_id VARCHAR(120) ... GENERATED ALWAYS AS (CASE WHEN status IN ('TEMPORARIO','DISPONIVEL') THEN client_upload_id ELSE NULL END) STORED`. Create the dedupe unique and composite active-upload unique `(proposito, referencia_dominio_id, criado_por, active_client_upload_id)`. Add the same receipt, upload lease fields, `(id, obra_id, sha256)` unique, and composite RDO attachment FK. Use `DATETIME(6)` for receipt/upload-lease/integrity/deletion instants, normalize every connection/write to UTC, and map those columns to `Instant` in `StoredObjectRecord`/`StoredObjectUploadReceipt`/`JdbcStoredObjectRepository`.

Build a deduplicated candidate set for legacy rows from Mensagens (`mensagem_anexo -> mensagem.conversa_id`) and all Financeiro stored-object relations. Exactly one distinct `(proposito, referencia_dominio_id, obra_id)` candidate may be backfilled. A row with no candidate becomes `LEGADO` with null reference. A row with two or more distinct candidates becomes `LEGADO`; if it is not already `ARQUIVADO`, set it to `QUARENTENA` with safe integrity error `AMBIGUOUS_LEGACY_BINDING`. Preserve an archived row as archived. Run the same classification algorithm and assertions in PostgreSQL and MySQL; never use `LIMIT 1`, aggregate order, or last-write-wins to select a binding. Both policy registries must deny `LEGADO`.

Update `PostgresqlSchemaVersion.REQUIRED`, YAML, clean-start sequence, release-marker max-version assertion, readiness tests, profile tests, and configuration-guard expectations from 65 to 66. Keep the MySQL sequence at V46 and extend `MigrationVersionUniquenessTest` so both migration directories are checked independently.

- [ ] **Step 5: Run both GREEN gates**

Run PostgreSQL/hermetic:

```bash
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
  ./mvnw -q -Dtest='PostgresqlStoredObjectLifecycleIT,StoredObjectLifecycleMysqlMigrationTest,PostgresqlFoundationContractTest,PostgresqlEffectiveConfigurationTest,PostgresqlSchemaReadinessGuardTest,PostgresqlRuntimeReadinessGuardTest,PostgresqlProfileModesContractTest,PostgresqlModeConfigurationGuardTest,PostgresqlActivationReadinessTest,PostgresqlCleanStartFlowIT,PostgresqlReleaseMarkerIT,MigrationVersionUniquenessTest' test
```

Run real MySQL:

```bash
cd apps/api
test -n "${CORTEX_MYSQL_ROOT_PASSWORD:-}" || exit 1
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
  ./mvnw -q -Dtest='StoredObjectLifecycleMysqlIntegrationTest,SharedStorageMigrationMysqlIntegrationTest' test
```

Expected: PASS with PostgreSQL latest Flyway version 66, MySQL latest version 46, immutable upload receipt and active identity constraints, retry after quarantine, exact composite FKs, deterministic single/ambiguous legacy classification, and no edit to an applied migration.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V66__rdo_object_transport_integrity.sql apps/api/src/main/resources/db/migration/V46__rdo_object_transport_integrity.sql apps/api/src/test/java/com/projeto/cortex/storage/PostgresqlStoredObjectLifecycleIT.java apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectLifecycleMysqlMigrationTest.java apps/api/src/test/java/com/projeto/cortex/pdor/StoredObjectLifecycleMysqlIntegrationTest.java apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java apps/api/src/main/resources/application-postgresql-common.yml apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectRecord.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectUploadReceipt.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectRepository.java apps/api/src/main/java/com/projeto/cortex/storage/JdbcStoredObjectRepository.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoCreateRequest.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentService.java apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationReadinessTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlEffectiveConfigurationTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlFoundationContractTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuardTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlProfileModesContractTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuardTest.java apps/api/src/test/java/com/projeto/cortex/migration/MigrationVersionUniquenessTest.java apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlReleaseMarkerIT.java
git commit -m "feat(storage): add V66 and V46 domain integrity parity"
```

### Task 6: Make Upload Replay Crash-Safe, Close Generic-Object BOLA, and Verify SHA

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectPurpose.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectAccessPolicy.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectPolicyRegistry.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectIntegrityVerifier.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/ObjectStoragePutResult.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoStoredObjectAccessPolicy.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/mensagens/domain/MessageStoredObjectAccessPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/FinanceStoredObjectAccessGuard.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/ObjectStorage.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/S3ObjectStorage.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/LocalObjectStorage.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/storage/PostgresqlStoredObjectUploadIdempotencyIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoAttachmentServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectControllerTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/LocalObjectStorageTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/security/Cortex3ObjectAuthorizationTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/security/FinanceStoredWorksiteAuthorizationTest.java`

**Interfaces:**
- Produces: one registered `StoredObjectAccessPolicy` per `StoredObjectPurpose`, with `authorizeUpload`, `authorizeRead`, and `authorizeDelete`.
- Produces RDO routes `POST /api/rdos/{rdoId}/anexos/uploads`, `GET /api/rdos/{rdoId}/anexos/{attachmentId}/conteudo`, and `DELETE /api/rdos/{rdoId}/anexos/{attachmentId}`.

- [ ] **Step 1: Write failing purpose, BOLA, and integrity tests**

```java
assertThatCode(() -> rdoPolicy.authorizeRead(rdoObject, unrelatedAuthenticatedUser))
        .doesNotThrowAnyException();
StoredObjectRecord firstUpload = service.upload(
        rdoUpload(PURPOSE, RDO_ID, ACTOR, CLIENT_UPLOAD_ID, HASH, SIZE, METADATA));
StoredObjectRecord exactReplay = service.upload(
        rdoUpload(PURPOSE, RDO_ID, ACTOR, CLIENT_UPLOAD_ID, HASH, SIZE, METADATA));
assertThat(exactReplay.id()).isEqualTo(firstUpload.id());
assertThatThrownBy(() -> service.upload(
        rdoUpload(PURPOSE, RDO_ID, ACTOR, CLIENT_UPLOAD_ID,
                DIFFERENT_HASH, DIFFERENT_SIZE, METADATA)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(CONFLICT));
verify(objectStorage, times(1))
        .putIfAbsent(any(), any(), anyLong(), any());
assertThat(receiptCount(CLIENT_UPLOAD_ID)).isEqualTo(1);
assertThat(storedObjectCount(CLIENT_UPLOAD_ID)).isEqualTo(1);
assertThatThrownBy(() -> messagePolicy.authorizeRead(messageObject, outsider))
        .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(FORBIDDEN));
assertThatThrownBy(() -> service.download(legacyObject.id()))
        .hasMessageContaining("domínio");
assertThatThrownBy(() -> service.download(ambiguousLegacyObject.id()))
        .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(FORBIDDEN));
assertThatThrownBy(() -> service.download(corruptedObject.id()))
        .hasMessageContaining("integridade");
verify(repository).markCorrupt(eq(corruptedObject.id()), any());
```

In `PostgresqlStoredObjectUploadIdempotencyIT`, race two first requests and inject crashes (a) after committed reservation/before R2 and (b) after R2/before `DISPONIVEL`. Assert both resume the same object ID/key, leave one receipt and one stored-object row, and perform one physical create. In `RdoAttachmentServiceTest`, assert attachment binding uses that returned object and cannot substitute another ID/hash/worksite.

- [ ] **Step 2: Run the RED gate**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -q -Dtest='StoredObjectServiceTest,PostgresqlStoredObjectUploadIdempotencyIT,RdoAttachmentServiceTest,StoredObjectControllerTest,S3ObjectStorageTest,LocalObjectStorageTest,Cortex3ObjectAuthorizationTest,FinanceStoredWorksiteAuthorizationTest' test`

Expected: FAIL because upload has no immutable receipt/conditional-create recovery, the generic service can still fall back to owner/worksite, and download checks only size/media type.

- [ ] **Step 3: Implement single-policy resolution and staged verification**

Replace `List<StoredObjectDomainAccessGuard>` with an enum-keyed registry that fails startup on duplicate/missing policies. RDO upload accepts a client-generated RDO ID plus an existing `obraId` without requiring a user-worksite link; later binding requires exact purpose, RDO reference, worksite, size, and SHA. Mensagens queries active conversation participation; Financeiro delegates to its current guard; zero-candidate and ambiguous backfills remain `LEGADO` and always return 403.

For every upload, resolve the actor from the validated session and stage/hash bytes before reserving storage. Build an immutable canonical metadata digest from purpose, domain reference, worksite, detected/declared media types, size, and content SHA. In a short first transaction, lock/read or atomically insert `stored_object_upload_receipt` plus one `TEMPORARIO` object by `(purpose, domainReference, actorId, clientUploadId)` **before** any R2 operation; commit that reservation. Derive the immutable storage key from server-generated `objectId + sha256` under the purpose prefix, never from `clientUploadId` alone.

Claim the reserved row with a bounded lease before physical upload. Add `ObjectStorage.putIfAbsent`, implemented with S3/R2 conditional create (`If-None-Match: *`) and local atomic create-new. The claim holder performs the only physical create; concurrent callers reload the receipt and return/wait for the same object. An exact hash/size/metadata replay returns or resumes the receipt's original object/result. A mismatch returns 409 **before** claim/`putIfAbsent` and changes neither receipt, object metadata, nor bytes. If a process crashes before R2, the next expired-lease claimant writes the same key; if it crashes after R2, conditional create reports `ALREADY_EXISTS`, the service verifies existing size/SHA, and marks the same object available. It never overwrites. Keep receipt/object/physical-create counts at one in replay, race, and crash tests.

For downloads, stage at most 25 MiB to a private temp file, hash and validate it fully, persist `VERIFICADA` or `CORROMPIDA`, then return a closeable stream that removes the temp file. The generic controller delegates to the registry and has no owner/worksite fallback.

- [ ] **Step 4: Run the GREEN gate**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -q -Dtest='StoredObjectServiceTest,PostgresqlStoredObjectUploadIdempotencyIT,RdoAttachmentServiceTest,StoredObjectControllerTest,StoredObjectContentInspectorTest,S3ObjectStorageTest,LocalObjectStorageTest,Cortex3ObjectAuthorizationTest,FinanceStoredWorksiteAuthorizationTest,RdoOperationalEventServiceTest' test`

Expected: PASS; identical upload replay returns one object/one R2 write, divergent bytes or metadata return 409 without overwrite, an authenticated outsider can read an RDO attachment, cannot read a conversation/finance/legacy object, and corrupt bytes never reach the response body.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectPurpose.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectAccessPolicy.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectPolicyRegistry.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectIntegrityVerifier.java apps/api/src/main/java/com/projeto/cortex/storage/ObjectStoragePutResult.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectService.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectController.java apps/api/src/main/java/com/projeto/cortex/storage/ObjectStorage.java apps/api/src/main/java/com/projeto/cortex/storage/S3ObjectStorage.java apps/api/src/main/java/com/projeto/cortex/storage/LocalObjectStorage.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoStoredObjectAccessPolicy.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentController.java apps/api/src/main/java/com/projeto/cortex/mensagens/domain/MessageStoredObjectAccessPolicy.java apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/FinanceStoredObjectAccessGuard.java apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectServiceTest.java apps/api/src/test/java/com/projeto/cortex/storage/PostgresqlStoredObjectUploadIdempotencyIT.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoAttachmentServiceTest.java apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectControllerTest.java apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java apps/api/src/test/java/com/projeto/cortex/storage/LocalObjectStorageTest.java apps/api/src/test/java/com/projeto/cortex/security/Cortex3ObjectAuthorizationTest.java apps/api/src/test/java/com/projeto/cortex/security/FinanceStoredWorksiteAuthorizationTest.java
git commit -m "fix(storage): enforce purpose policy before verified download"
```

### Task 7: Remove RDO Objects with One Authoritative Event and Show Per-Item Sync State

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectDeletionService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectDeletionScheduler.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentRemovalRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentRemovalResult.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectDeletionServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/PostgresqlRdoAttachmentDeletionOntologyIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoAttachmentControllerAuthoritativeTraceMockMvcTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/JdbcStoredObjectRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentService.java`
- Modify (created in Task 6): `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentController.java`
- Modify (created in Plan 01): `apps/api/src/main/java/com/projeto/cortex/rdos/RdoMutationTraceFactory.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoMemoryPublisher.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalEventService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoOperationalEventServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java`
- Create: `apps/web/src/lib/sync/rdoAttachmentRemovalMutation.test.ts`
- Modify: `apps/web/src/lib/db/rdoAttachmentRepository.ts`
- Modify: `apps/web/src/lib/db/localRdoService.ts`
- Modify: `apps/web/src/lib/sync/localMutationCoordinator.ts`
- Modify: `apps/web/src/lib/sync/useSyncStatus.ts`
- Modify (created in Plan 01): `apps/web/src/components/SyncStatusBanner.test.tsx`
- Modify: `apps/web/src/components/SyncStatusBanner.tsx`

**Interfaces:**
- Produces: `RdoAttachmentRemovalRequest(clientMutationId, baseVersion, declaredAt)` and `RdoAttachmentRemovalResult(clientMutationId, eventCommitSeq, baseVersion, resultVersion, status)`.
- Requires matching `Idempotency-Key` and request `clientMutationId`; the server canonicalizes `{operation, rdoId, attachmentId, baseVersion, declaredAt}` and calculates its payload hash.
- Produces: `requestDeletionForUnreferencedObject(objectId)` and `reconcileDueDeletions(limit)`.
- Extends: `SyncStatusSnapshot.problems: SyncProblemItem[]`, keyed by `clientMutationId`, with stable status/reason.

- [ ] **Step 1: Write failing authoritative-removal, replay, deletion, and per-item banner tests**

```java
RdoAttachmentRemovalResult first = remove(
        RDO_ID, ATTACHMENT_ID, MUTATION_ID, BASE_VERSION, session(ACTOR, DEVICE));
RdoAttachmentRemovalResult replay = remove(
        RDO_ID, ATTACHMENT_ID, MUTATION_ID, BASE_VERSION, session(ACTOR, DEVICE));
assertThat(replay).isEqualTo(first);
assertThat(eventCount("RDO_ANEXO_REMOVIDO", MUTATION_ID)).isEqualTo(1);
assertThat(event(MUTATION_ID))
        .extracting("usuario_id", "dispositivo_id", "versao_base",
                "versao_resultante", "payload_hash")
        .containsExactly(ACTOR, DEVICE, BASE_VERSION,
                first.resultVersion(), EXPECTED_SERVER_HASH);
assertThat(objectDeletionTransitionCount(OBJECT_ID)).isEqualTo(1);
assertThatThrownBy(() -> service.download(OBJECT_ID))
        .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(NOT_FOUND));
deletionService.reconcileDueDeletions(20);
verify(storage).delete(STORAGE_KEY);
verify(repository).markArchived(OBJECT_ID);
```

Also send forged actor/name/device/hash in the MVC body and assert none reaches the event. Replay with another owner/device or a different canonical hash must fail without revealing the original receipt. A stale `baseVersion` must return `CONFLITO`; the exact replay lookup happens before CAS, so an already-applied request returns the original `eventCommitSeq` and versions.

```tsx
expect(screen.getByText("RDO 128 — revisão necessária")).toBeVisible();
expect(screen.getByText("Foto do RDO 129 — nova tentativa automática")).toBeVisible();
expect(screen.getByRole("button", { name: "Sincronizar agora" })).toBeEnabled();
```

- [ ] **Step 2: Run the RED gate**

Run backend: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -q -Dtest='PostgresqlRdoAttachmentDeletionOntologyIT,RdoAttachmentControllerAuthoritativeTraceMockMvcTest,StoredObjectDeletionServiceTest,RdoOperationalEventServiceTest,S3ObjectStorageTest' test`

Run web: `cd apps/web && npm test -- src/lib/sync/rdoAttachmentRemovalMutation.test.ts src/components/SyncStatusBanner.test.tsx src/lib/sync/useSyncStatus.test.ts`

Expected: FAIL because removal has no stable request/authoritative event contract, deletion is synchronous/absent, and the banner exposes only one aggregate reason.

- [ ] **Step 3: Implement one idempotent domain mutation, immediate tombstone, retrying physical delete, and item presentation**

The PWA generates one immutable `clientMutationId`, stores the removal mutation in the owner/device authoring partition, and reuses it on every retry. The route requires matching `Idempotency-Key`, ignores client actor/name/device/hash fields, resolves actor/name and client instance from `ResolvedAuthSession`, and uses the registered sync device for offline replay. `RdoMutationTraceFactory` canonicalizes the operation and opens the Plan 01 trace with server-calculated hash, declared/received/applied instants, and base version.

In one PostgreSQL transaction, lock the RDO/attachment, check the owner+device+mutation receipt before CAS, mark the attachment removed, mark an unreferenced object `EXCLUSAO_PENDENTE`, advance the authoritative RDO entity version, record exactly one `RDO_ANEXO_REMOVIDO` event, and persist its `eventCommitSeq`, base/result versions, and payload hash in the replay result. Return 202 with that result. The event's `resumo_seguro` may contain only the event type and RDO/attachment identifiers; it cannot contain file name, user input, or storage key. An exact replay returns the stored result even after the RDO version advanced and does not invoke storage/deletion/event writes again. A divergent hash, different owner/device, or stale non-replay base version returns the existing safe rejection/conflict contract. Do not pass this deletion as an additional client operational event; the server event is the only mutation event.

Reads return 404 immediately. The reconciler claims due rows with `FOR UPDATE SKIP LOCKED`, deletes the exact server-resolved storage key, then archives metadata; on failure it increments attempts and sets bounded exponential backoff without making the attachment readable again. Physical archive is a technical continuation of the already-recorded removal and must not create a second user-authored RDO event. Add orphan cleanup for RDO-purpose uploads that never gained an active attachment. In the PWA, retain automatic retryable items and review-only items separately; render each safe reason while keeping “Sincronizar agora” as an optional accelerator.

- [ ] **Step 4: Run the GREEN gate**

Run backend: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -q -Dtest='PostgresqlRdoAttachmentDeletionOntologyIT,RdoAttachmentControllerAuthoritativeTraceMockMvcTest,StoredObjectDeletionServiceTest,RdoOperationalEventServiceTest,StoredObjectServiceTest,StoredObjectControllerTest,S3ObjectStorageTest' test`

Run web: `cd apps/web && npm test -- src/lib/sync/rdoAttachmentRemovalMutation.test.ts src/components/SyncStatusBanner.test.tsx src/lib/sync/useSyncStatus.test.ts src/lib/sync/automaticSyncScheduler.test.ts src/lib/sync/automaticSyncRetryStorage.test.ts`

Expected: PASS; exact replay preserves actor/device/hash/times/versions and one event, logical removal is immediate, physical removal retries safely, and one rejected item no longer presents the entire sync run as lost.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectDeletionService.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectDeletionScheduler.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentRemovalRequest.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentRemovalResult.java apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectRepository.java apps/api/src/main/java/com/projeto/cortex/storage/JdbcStoredObjectRepository.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentController.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoMutationTraceFactory.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoMemoryPublisher.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalEventService.java apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectDeletionServiceTest.java apps/api/src/test/java/com/projeto/cortex/rdos/PostgresqlRdoAttachmentDeletionOntologyIT.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoAttachmentControllerAuthoritativeTraceMockMvcTest.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoOperationalEventServiceTest.java apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java apps/web/src/lib/sync/rdoAttachmentRemovalMutation.test.ts apps/web/src/lib/db/rdoAttachmentRepository.ts apps/web/src/lib/db/localRdoService.ts apps/web/src/lib/sync/localMutationCoordinator.ts apps/web/src/lib/sync/useSyncStatus.ts apps/web/src/components/SyncStatusBanner.tsx apps/web/src/components/SyncStatusBanner.test.tsx
git commit -m "feat(storage): audit and reconcile RDO attachment removal"
```

### Task 7A: Prepare the Sanitized Acceptance Harness Before Plan 05

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StorageAcceptanceProofRunner.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/StorageAcceptanceProofResult.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/storage/StorageAcceptanceProofRunnerTest.java`
- Modify (updated in Task 6): `apps/api/src/main/java/com/projeto/cortex/storage/ObjectStorage.java`
- Modify (updated in Task 6): `apps/api/src/main/java/com/projeto/cortex/storage/S3ObjectStorage.java`
- Modify (updated in Task 6): `apps/api/src/main/java/com/projeto/cortex/storage/LocalObjectStorage.java`
- Modify (updated in Tasks 6–7): `apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java`
- Modify (updated in Task 6): `apps/api/src/test/java/com/projeto/cortex/storage/LocalObjectStorageTest.java`
- Create: `docs/runbooks/cortex-offline-r2-acceptance.md`
- Create: `scripts/qa/verify-offline-r2-evidence.sh`
- Create: `scripts/qa/test-verify-offline-r2-evidence.sh`
- Modify: `docs/production-runbook.md`

**Interfaces:**
- Server runner input: one `storedObjectId` and expected release SHA, passed only inside the deployed API runtime. It never accepts a storage key or output path; its main writes one sanitized JSON document to stdout so the operator controls a mode-0600 redirect.
- Server runner output: `StorageAcceptanceProofResult(releaseSha, objectIdDigest, purpose, metadataStatus, objectAbsent, checkedAt)`; it never returns the storage key, endpoint, bucket, access key, secret, or credentials.
- Orchestrator inputs: `CORTEX_QA_ORIGIN`, permission-checked cookie/CSRF files, RDO/attachment IDs, non-PII fixture, `CORTEX_QA_PG_SERVICE`, `CORTEX_QA_SERVER_STORAGE_PROOF_FILE`, the Plan-02 `CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE` and `CORTEX_QA_EVIDENCE_FILE`, and an evidence directory outside the Git worktree.
- Orchestrator output: a bounded sanitized JSON manifest containing release SHA, UTC timestamps, counts, statuses, and SHA-256 digests of identifiers/file.

- [ ] **Step 1: Write the failing server-proof and evidence-script contract tests**

```java
StorageAcceptanceProofResult proof = runner.verify(ARCHIVED_RDO_OBJECT_ID);
assertThat(proof.objectAbsent()).isTrue();
assertThat(proof.objectIdDigest()).hasSize(64);
assertThat(serialized(proof))
        .doesNotContain(STORAGE_KEY)
        .doesNotContain("accessKey")
        .doesNotContain("secret");
verify(objectStorage).exists(EXACT_SERVER_RESOLVED_KEY);
```

```bash
assert_manifest '.offline.localSave == true'
assert_manifest '.offline.automaticReplay == true'
assert_manifest '.postgres.rdoCount == 1'
assert_manifest '.postgres.ontologyEventCount == 1'
assert_manifest '.postgres.removalEventCount == 1'
assert_manifest '.r2.uploadSha256 == .r2.downloadSha256'
assert_manifest '.r2.afterDeleteStatus == 404'
assert_manifest '.r2.serverProof.objectAbsent == true'
assert_manifest '.release.sha == .r2.serverProof.releaseSha'
assert_manifest '.release.sha == .academy.source.releaseSha'
assert_manifest '.release.sha == .academy.http.releaseSha'
assert_manifest '.academy.source.selectOnly == true'
assert_manifest '.academy.http.activeSession == true'
assert_manifest '.academy.http.inactiveCode == "ACADEMY_ACCESS_INACTIVE"'
assert_manifest '.academy.pollingEnabled == false'
assert_absent 'cpf|email|nome|cookie|token|secret|password|storageKey|bucket|endpoint'
```

Add negative tests: arbitrary storage-key input is rejected; non-RDO, non-`ARQUIVADO`, release-SHA mismatch, storage backend other than production R2, `exists=true`, missing server proof, missing or mismatched Academy source/HTTP proof, an Academy proof that reports polling enabled, a `READY`-only manifest, and evidence paths inside the Git worktree all fail closed.

- [ ] **Step 2: Run the RED gate**

Run:

```bash
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
  ./mvnw -q -Dtest='StorageAcceptanceProofRunnerTest,S3ObjectStorageTest,LocalObjectStorageTest' test
cd ../..
bash scripts/qa/test-verify-offline-r2-evidence.sh
```

Expected: FAIL because the server-side absence verifier, wrappers, and sanitized manifest contract do not exist.

- [ ] **Step 3: Implement the server-only R2 proof, orchestrator, and runbook**

Add `ObjectStorage.exists(key)` with a safe default that closes any opened content; override it in `S3ObjectStorage` with `HeadObject` and in `LocalObjectStorage` with the same traversal/symlink defenses as `get`. Map only provider 404/`NoSuchKey` to `false`; authorization, timeout, DNS, and provider failures throw and therefore fail the proof.

`StorageAcceptanceProofRunner` is disabled by default and exposes a dedicated `main` that boots Spring with `WebApplicationType.NONE` only when explicit acceptance mode is present inside the deployed API container. It resolves `storedObjectId` through `StoredObjectRepository`, requires `purpose=RDO`, `status=ARQUIVADO`, the production R2 backend, and the exact current `CORTEX_RELEASE_REVISION`, then invokes the configured `ObjectStorage.exists`/HEAD operation on the repository-resolved key. It emits exactly one `StorageAcceptanceProofResult` JSON document to stdout, sends no application log there, and exits nonzero when the object exists or metadata/revision is inconsistent. It cannot probe an arbitrary key and exposes no HTTP route.

`StorageAcceptanceProofRunnerTest` proves the main refuses missing mode/object/SHA, never starts a web server, writes JSON only after all checks pass, and can be launched with the existing `/app/app.jar` plus Spring Boot `PropertiesLauncher`; no repository-level script is assumed to exist in the runtime image. `verify-offline-r2-evidence.sh` consumes only the transferred sanitized server result and the two bounded Plan-02 Academy evidence documents; it never loads R2/Academy credentials, CPF, OTP or a raw storage key. Require all three evidence files to be mode 0600, contain the same expected release SHA and expose only the allowlisted aggregate fields. Cookie/CSRF material is read only from mode-0600 files, PostgreSQL uses an externally configured `psql` service, and all generated evidence must be outside the Git worktree.

The runbook defines the official manual path but clearly marks it deferred until Task 8:

1. Record deployed revision and technical readiness, labeling readiness “technical probe only”.
2. Run both deferred Plan-02 Academy proofs against the published SHA, including active login, exact inactive denial, SELECT-only/TLS/index checks and the bounded outage rehearsal; retain only the two sanitized mode-0600 evidence documents.
3. Log in with the active Academy QA collaborator from Plan 02; record only a one-way identifier digest.
4. Open any existing worksite without relying on a worksite link, block network, create an RDO, add one new operational person and one unlinked worker, attach the fixed non-PII fixture, and save without pressing “Sincronizar agora”.
5. Confirm the stable owner/device authoring partition contains draft, event, upload, and RDO mutation.
6. Restore general network while `/api/sync/*` is blocked, refresh to a different session worksite scope for the same user, and confirm the same partition/mutation remains.
7. Unblock `/api/sync/*`, foreground the PWA, and observe automatic replay.
8. Verify one PostgreSQL RDO, one operational collaborator registration, one canonical create event with actor/device/original-received-applied times, and replay count one for the same mutation.
9. Restart the PWA, download the authenticated RDO attachment, compare its SHA-256 with the fixture/upload hash, then remove it with a stable `clientMutationId`; verify one authoritative removal event and wait for `ARQUIVADO`.
10. Run the server-only absence proof inside the deployed API runtime. Verify attachment GET is 404, unauthenticated RDO GET is 401, a nonparticipant Mensagens object is 403, unauthorized Financeiro is 403, and generic/ambiguous `LEGADO` is 403.
11. Save identity-redacted screenshots, the sanitized manifest, workflow URL, and published SHA outside the repository.

- [ ] **Step 4: Run the GREEN gate**

Run:

```bash
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
  ./mvnw -q -Dtest='StorageAcceptanceProofRunnerTest,S3ObjectStorageTest,LocalObjectStorageTest' test
cd ../..
bash scripts/qa/test-verify-offline-r2-evidence.sh
```

Expected: PASS; tests prove fail-closed server resolution and sanitized handoff without contacting production.

- [ ] **Step 5: Commit only implementation, tests, and runbook contracts**

```bash
git add apps/api/src/main/java/com/projeto/cortex/storage/StorageAcceptanceProofRunner.java apps/api/src/main/java/com/projeto/cortex/storage/StorageAcceptanceProofResult.java apps/api/src/test/java/com/projeto/cortex/storage/StorageAcceptanceProofRunnerTest.java apps/api/src/main/java/com/projeto/cortex/storage/ObjectStorage.java apps/api/src/main/java/com/projeto/cortex/storage/S3ObjectStorage.java apps/api/src/main/java/com/projeto/cortex/storage/LocalObjectStorage.java apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java apps/api/src/test/java/com/projeto/cortex/storage/LocalObjectStorageTest.java docs/runbooks/cortex-offline-r2-acceptance.md docs/production-runbook.md scripts/qa/verify-offline-r2-evidence.sh scripts/qa/test-verify-offline-r2-evidence.sh
git commit -m "test(prod): define offline replay and server-side R2 proof"
```

Do not create or collect live evidence here. After this commit, execute Plan 05 completely before publication.

## Release Interlock Before Task 8

- [ ] Complete every Plan 05 task and its browser acceptance. Do not publish the Plan 04-only SHA.
- [ ] With a clean worktree at the candidate HEAD, run the complete local gates, not only focused tests:

```bash
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw test
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
  ./mvnw -Ppostgresql-it verify
cd ../..
npm --prefix apps/web test
npm --prefix apps/web run lint
npm --prefix apps/web run build
bash scripts/security/test-production-publication.sh
bash scripts/security/scan-cortex-secrets.sh
git diff --check
git status --short
```

- [ ] Require Docker/Testcontainers and `CORTEX_MYSQL_ROOT_PASSWORD`; a skipped PostgreSQL or MySQL integration suite is not a green release gate.
- [ ] Push the clean candidate SHA and run the official production workflow once. Require every job green, including full API/web suites, secret scan, image build/attestation, Neon V66 migration, Render deployment, and Cloudflare Pages deployment.
- [ ] Record the workflow URL and verify provider deployment metadata, the PostgreSQL release marker, Render revision, and Cloudflare deployment all identify the same candidate SHA. If any surface differs, do not begin Task 8.

### Task 8: Run the Post-Publication Offline, R2, and Visual Acceptance on One SHA

**Files:** None. This is an evidence gate against the already-published immutable SHA. Evidence is written only to the approved external QA location; there is no `git add` or commit.

**Prerequisites:**
- Plans 01–05 are complete.
- The Release Interlock is green and the official origin serves the candidate SHA from both Cloudflare and Render.
- The server-side proof command is available inside the deployed API runtime; R2 credentials remain there.
- The Plan-02 active/inactive CPF files, OTP operator, SELECT-only Academy credentials, QA-replica outage controls and two external mode-0600 Academy evidence destinations are available without entering the Git worktree.

- [ ] **Step 1: Freeze and verify the published SHA**

Set `CORTEX_QA_RELEASE_SHA` from the green workflow output. Compare it with the Git candidate, provider deployment metadata, PostgreSQL release marker, Render revision, and Cloudflare deployment. Assert the worktree is clean. Stop on any mismatch.

- [ ] **Step 2: Execute the deferred Academy JIT proof on the same SHA**

From the clean candidate checkout, run the Plan-02 source/TLS/SELECT-only proof and then the official HTTP CPF/OTP proof:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  CORTEX_ACADEMY_JIT_QA_ENABLED=true \
  CORTEX_QA_RELEASE_SHA="$CORTEX_QA_RELEASE_SHA" \
  CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE="$CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE" \
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
    ./mvnw -Dtest='AcademyJitLiveAccessIT' test
)
CORTEX_QA_RELEASE_SHA="$CORTEX_QA_RELEASE_SHA" \
CORTEX_QA_EVIDENCE_FILE="$CORTEX_QA_EVIDENCE_FILE" \
CORTEX_JIT_BASE_URL='https://cortex-stavias.pages.dev/api' \
  bash scripts/qa/verify-academy-jit-login.sh
```

The Java test reads the Plan-02 `CORTEX_ACADEMY_JIT_QA_ACTIVE_CPF_FILE`/`CORTEX_ACADEMY_JIT_QA_INACTIVE_CPF_FILE`; the HTTP proof reads `CORTEX_QA_ACTIVE_CPF_FILE`/`CORTEX_QA_INACTIVE_CPF_FILE` and obtains OTP only from `/dev/tty`. Follow `docs/qa/academy-jit-otp-acceptance.md` to run the bounded outage rehearsal on a QA replica at this same build: an existing session remains 200 while a new session fails with the approved 503. Require active Beta session, exact inactive message, polling false, mode-0600 evidence, no identifier, and the same release SHA in both documents.

- [ ] **Step 3: Execute the official manual offline and R2 path**

Follow `docs/runbooks/cortex-offline-r2-acceptance.md` end to end on the official origin. Do not press “Sincronizar agora”: automatic replay is the acceptance path. Prove the stable owner/device partition survives a worksite-scope refresh, replay creates exactly one RDO/collaborator/create event, removal creates exactly one authoritative `RDO_ANEXO_REMOVIDO` event with actor/device/hash/base/result versions, authenticated download SHA equals the fixture/upload SHA, and authorization negatives return the specified statuses.

- [ ] **Step 4: Prove exact R2 absence server-side**

Inside a Render Shell for the deployed API runtime, invoke the runner directly from the JAR that the image actually contains; do not assume repository scripts are copied into the image:

```bash
umask 077
CORTEX_STORAGE_ACCEPTANCE_MODE=true \
CORTEX_QA_RELEASE_SHA="$CORTEX_QA_RELEASE_SHA" \
CORTEX_QA_STORED_OBJECT_ID="$CORTEX_QA_STORED_OBJECT_ID" \
  java \
    -Dspring.main.web-application-type=none \
    -Dspring.main.banner-mode=off \
    -Dlogging.level.root=OFF \
    -Dloader.main=com.projeto.cortex.storage.StorageAcceptanceProofRunner \
    -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    > /tmp/cortex-r2-absence-proof.json
chmod 600 /tmp/cortex-r2-absence-proof.json
```

Inspect the bounded JSON allowlist, then transfer only that sanitized document through the approved encrypted QA channel into the external mode-0600 `CORTEX_QA_SERVER_STORAGE_PROOF_FILE`. The runner must prove the repository-resolved RDO object is `ARQUIVADO`, its exact R2 key is absent, and the proof revision equals `CORTEX_QA_RELEASE_SHA`. Never copy R2 credentials or the resolved key out of the API runtime.

- [ ] **Step 5: Record the official visual proof on the same SHA**

Repeat Plan 05's real-data browser acceptance on the published origin at 390, 1100, and 1440 px, including the compact Obras badge, actor line, per-item sync state, Academy inactive/error states, and all protected recovery/offline/conflict/privacy copy. Capture only identity-redacted screenshots. Every screenshot manifest entry must carry `CORTEX_QA_RELEASE_SHA`; a screenshot from another deployment is invalid.

- [ ] **Step 6: Validate the combined sanitized evidence**

Run:

```bash
CORTEX_QA_RELEASE_SHA="$CORTEX_QA_RELEASE_SHA" \
CORTEX_QA_SERVER_STORAGE_PROOF_FILE="$CORTEX_QA_SERVER_STORAGE_PROOF_FILE" \
CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE="$CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE" \
CORTEX_QA_EVIDENCE_FILE="$CORTEX_QA_EVIDENCE_FILE" \
  bash scripts/qa/verify-offline-r2-evidence.sh
```

Expected: exit 0 only when Academy active/inactive/source/TLS/SELECT-only/outage evidence, polling false, automatic replay, PostgreSQL/ontology counts, authenticated SHA-equal download, authoritative idempotent removal, asynchronous delete, server-side R2 absence, authorization negatives, visual manifest, green workflow URL, and one matching published SHA are all present. A readiness `READY`, unit-test output, different Academy/visual SHA, or client-side R2 claim must fail.

- [ ] **Step 7: Close without changing Git**

Run `git status --short` and require the same clean tree. Archive sanitized evidence in the approved external QA store; never commit live manifest, screenshots, cookie jar, CSRF material, `psql` service file, object key, fixture, or raw logs. If acceptance finds a defect, fix it in the owning Plan 01–05 task, rerun complete gates, publish a new SHA, and repeat Task 8 from Step 1.

## Completion Gate

- [ ] `git diff --check` is clean.
- [ ] `rg -n 'cortex-authoring-v1.*obra|cortex-authoring-v1.*papel|authorizationScope.*databaseName' apps/web/src` returns no matches.
- [ ] PostgreSQL V66 and MySQL V46 both enforce active-only dedupe and the exact `(stored_object_id, obra_id, sha256)` binding; a real MySQL integration gate ran rather than skipped.
- [ ] Upload replay/race/crash keeps one immutable receipt, one object row, one physical conditional create, and one `objectId + sha256` storage key; divergent bytes or metadata return 409 before any R2 write.
- [ ] Single legacy bindings classify deterministically; zero-candidate and ambiguous multi-domain/reference objects stay denied as `LEGADO`, and active ambiguous rows are quarantined.
- [ ] Replaying the same RDO-create `(ownerId, deviceId, clientMutationId)` leaves exactly one RDO, one collaborator registration, one applied mutation, and one authoritative creation event.
- [ ] Replaying one RDO attachment removal returns the same event commit/base/result versions and leaves exactly one logical deletion transition and one authoritative `RDO_ANEXO_REMOVIDO` event with server-resolved actor/device/hash/times.
- [ ] A scope refresh neither changes the authoring database name nor cancels automatic replay.
- [ ] Logout or a different user prevents every late write/ACK into another owner’s partition.
- [ ] A corrupt stored object is marked corrupt and returns no response bytes.
- [ ] Full API/web suites, lint, build, publication contracts, secret scan, and the official production workflow are green for the one published SHA.
- [ ] Cloudflare, Render, PostgreSQL release marker, Academy source/HTTP evidence, offline/R2 evidence, and visual evidence all name the same SHA.
- [ ] Exact R2-key absence is proven only by the server-side runner; no browser/orchestration artifact contains the key or credentials.
- [ ] The committed tree contains no QA cookie jar, CSRF file, PostgreSQL service file, raw evidence manifest, attachment fixture containing PII, `.ua/`, or `xcrun_db`.
- [ ] Task 8 leaves Git unchanged and archives evidence outside the repository.
- [ ] The final report distinguishes automated gates, technical readiness, green publication workflow, dated Academy JIT proof, dated official offline/R2 proof, and dated visual proof.

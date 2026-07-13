# Offline Messaging and Shared Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: implement task-by-task with `superpowers:subagent-driven-development`; every task starts with a focused failing test and ends with fresh verification and a commit.

**Goal:** Extend the existing IndexedDB/outbox/pull protocol into an authenticated, owner-partitioned offline data plane and deliver authorized direct/group/worksite messaging with durable local attachments and idempotent upload.

**Architecture:** Keep one sync engine. Add a backend `SyncMutationHandler` registry so RDO and messaging share push/pull semantics without a parallel protocol. Add a shared binary `ObjectStorage` abstraction (filesystem locally, S3-compatible in production), while the existing outbox coordinates metadata and binary-upload dependencies. Partition every local record, cursor and dedupe marker by authenticated owner. Server-side visibility is evaluated per event: worksite scope for RDO, participants for conversations, and financial grants for later finance handlers. Project all domain changes through `CortexOperationalMemoryService` with actor/device/correlation and before/after/result fields.

**Migrations:** only `V29__shared_storage_audit_and_sync.sql` and `V30__messaging_teams.sql`; never edit V1-V28.

**Cross-plan contracts:** reuse `AuthSessionService`/`CurrentUserService` and PRF `offlineVault` from the auth plan; reuse `ObjectStorage` and `SyncMutationHandler` from this plan in Financeiro; never create a second mail, storage, sync, or offline-unlock abstraction.

## Task 1: V29 shared object, event visibility, and sync idempotency schema

**Files:**
- Create `apps/api/src/main/resources/db/migration/V29__shared_storage_audit_and_sync.sql`
- Create `apps/api/src/test/java/com/projeto/cortex/sync/SharedStorageSyncMigrationTest.java`
- Modify MySQL migration integration fixture only if needed.

**RED:** assert V29 creates `stored_object`, `sync_mutation_receipt`, `cortex_event_visibility` and additive actor/device/correlation/result columns; assert no blob/base64 content column and no edits to prior migrations.

**GREEN:** create immutable object metadata keyed by UUID and SHA-256 with backend/key/media type/size/state; idempotent mutation receipts keyed by owner/device/clientMutationId; event visibility rows supporting `GLOBAL_ALFA`, `WORKSITE`, `CONVERSATION_PARTICIPANT`, `FINANCIAL_CAPABILITY`; indexes and FKs; additive audit columns. Run focused test plus disposable MySQL Flyway suite with JDK 21.

**Commit:** `feat(sync): add shared storage and scoped event schema`

## Task 2: ObjectStorage with local and S3-compatible providers

**Files:**
- Create `apps/api/src/main/java/com/projeto/cortex/storage/{ObjectStorage,StoredObject,StoredObjectService,LocalObjectStorage,S3ObjectStorage,StorageConfiguration}.java`
- Create upload/download controllers and DTOs under `storage/api`
- Modify `apps/api/pom.xml`, `application.yml`, `application-local.yml`, `.env.example`
- Add unit/integration tests under `apps/api/src/test/java/com/projeto/cortex/storage`

**RED:** tests cover SHA-256 integrity, size/type allowlist, traversal rejection, unauthorized read, repeated upload returning the same object, local atomic write, and production refusal of ephemeral/unconfigured storage.

**GREEN:** stream bytes outside MySQL; enforce configured maximum size and server-sniffed/declared media type policy; store to a temporary key then atomically finalize; S3 uses configured bucket/region/endpoint/path-style and credential chain/secret files; authorization callback owns reads. No production public bucket or filesystem fallback.

**Commit:** `feat(storage): add durable local and s3 object storage`

## Task 3: Generic scoped sync handler registry

**Files:**
- Create `apps/api/src/main/java/com/projeto/cortex/sync/SyncMutationHandler.java`, `SyncMutationContext.java`, `SyncEventVisibilityPolicy.java`, `SyncMutationReceiptRepository.java`
- Create `apps/api/src/main/java/com/projeto/cortex/sync/rdo/RdoSyncMutationHandler.java`
- Modify `SyncService`, sync request/response records, `SyncController`
- Modify `CortexOperationalMemoryService`
- Add handler/idempotency/visibility/audit tests.

**RED:** prove unknown entity/operation fails honestly; duplicate clientMutationId returns the original result without a second write/event; BETA cannot pull another worksite/conversation/financial event; one partial failure leaves the response visibly failed; audit records actor, source device, correlation, prior/new state and result.

**GREEN:** registry selects by entity type and operation; move existing RDO logic behind `RdoSyncMutationHandler` without behavior loss; guarded transactional receipt; pull query joins visibility policy before serialization; response carries per-mutation state/error, never unconditional success.

**Commit:** `refactor(sync): route mutations through scoped handlers`

## Task 4: Owner-partitioned IndexedDB and dependency-aware outbox

**Files:**
- Modify `apps/web/src/lib/db/cortexDb.ts`, `db.types.ts`, `syncStateRepository.ts`
- Modify `apps/web/src/lib/sync/{sync.types,syncStorage,pushOutbox,pullEvents,syncEngine}.ts`
- Add owner migration, dependency DAG, logout isolation, terminal failure and retry tests.

**RED:** create legacy v9 data then upgrade; prove a second owner cannot read first owner records/cursor/dedupe/outbox; dependent binary upload waits for parent; cyclic/missing dependency fails clearly; terminal failures remain visible and retryable; logout locks local data without deleting pending work.

**GREEN:** bump DB version add `ownerId` to every user-scoped store/index; migrate legacy records into a quarantined locked owner rather than guessing identity; all repository APIs require owner; outbox rows contain `transport`, dependencies, stable clientMutationId/hash and explicit `LOCAL|NA_FILA|SINCRONIZANDO|SINCRONIZADO|FALHOU`; process topologically; gate reads/sync through the PRF vault and valid session.

**Commit:** `feat(web): partition offline data and order sync dependencies`

## Task 5: V30 teams, conversations, messages, and attachment metadata

**Files:**
- Create `apps/api/src/main/resources/db/migration/V30__messaging_teams.sql`
- Create migration contract and MySQL integration tests.

**RED:** assert normalized `equipe`, `equipe_membro`, `conversa`, `conversa_participante`, `mensagem`, `mensagem_anexo`, searchable/indexed timestamps, soft-delete/version/audit fields, worksite membership constraints and no file body/base64.

**GREEN:** direct/group/team/worksite conversation types; unique direct participant set guard; participant roles and active interval; message clientMutationId uniqueness per author; attachment references `stored_object`; tombstones preserve history; event visibility ties each message/conversation to current participants.

**Commit:** `feat(mensagens): add conversation persistence model`

## Task 6: Messaging domain, authorization, REST, sync, and ontology projection

**Files:**
- Create packages `apps/api/src/main/java/com/projeto/cortex/mensagens/{domain,api,sync}`
- Add repositories/services/controllers/DTOs, `MensagemSyncMutationHandler`, `ConversaAccessPolicy`, ontology projector
- Modify sync registry and operational memory service
- Add service/MockMvc/sync/ontology authorization tests.

**RED:** ALFA access; BETA only if active participant and, for worksite/team conversations, active worksite/team membership; direct/group create validation; nonparticipant search/send/download denied; create/edit/soft-delete/message status/attachment all emit traceable events; duplicate offline push is idempotent; search cannot cross participant boundary.

**GREEN:** transactional conversation/member/message services; paged participant-scoped list/history/search; stable server/client timestamps; attachment finalize only after authorized object exists and hash matches; sync handler maps `CONVERSA`, `MENSAGEM`, `MENSAGEM_ANEXO`; projection records actor/device/correlation/before/after/result and relationship IDs.

**Endpoints:** `/api/mensagens/conversas`, `/{id}`, `/{id}/participantes`, `/{id}/mensagens`, `/api/mensagens/busca`, `/api/mensagens/anexos/{id}` plus existing `/api/sync/*`.

**Commit:** `feat(mensagens): add authorized messaging api and sync`

## Task 7: Offline-first Mensagens web UI

**Files:**
- Create `apps/web/src/features/mensagens/**` pages, repositories, API adapters, components and CSS/tests
- Modify `App.tsx`, `CortexShell.tsx`, sync status components and service worker caching only as required.

**RED:** component/repository tests prove immediate optimistic rendering while offline; persisted attachment after reload; Portuguese `LOCAL/NA FILA/SINCRONIZANDO/SINCRONIZADO/FALHOU`; manual retry; search/history/author/time/attachments; unauthorized worksite options absent but server remains boundary; responsive keyboard-accessible conversation UI; useful empty/error states.

**GREEN:** lazy-loaded `/mensagens`; restrained existing design tokens; desktop split pane, tablet adaptive pane, mobile drill-in; composer stages Blob in IndexedDB, queues metadata then binary/finalize dependencies; object URLs are revoked; no base64/localStorage credentials; sync detail explains pending/failure/session/reconnection actions in Portuguese.

**Commit:** `feat(web): deliver offline first mensagens`

## Task 8: Messaging and sync deployment evidence

**Files:**
- Modify dev/deploy docs, compose, smoke scripts and environment examples
- Add focused end-to-end test fixtures only under test source.

**Verification:** fresh JDK21 backend suite; sequential web tests/lint/build; Flyway V1-V30 from scratch and upgrade; local storage and configured S3 contract tests; two-user authorization; browser offline message + attachment reload + reconnect + exactly-once sync; no base64/blob/CPF/secret scan; Docker/compose health. Record external S3 credentials as the only provider-dependent verification gap if unavailable.

**Commit:** `docs(mensagens): document storage and offline operations`

## Completion Gate

- One outbox/protocol only, owner-partitioned and vault-gated.
- Pending data survives reload, logout and transient failures without leaking to another owner.
- Attachments stay outside MySQL and upload once with verified integrity.
- BETA never reads/sends/searches outside authorized worksite/team/conversation scope.
- Every mutation and sync attempt has complete correlated ontology/audit evidence.
- All visible strings are Portuguese and all test/build/deploy checks have fresh evidence.

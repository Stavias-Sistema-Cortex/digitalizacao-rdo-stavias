# Córtex Foundation + Teams Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Generalize the existing offline mutation pipeline without regressing RDOs and deliver the real Equipes domain end-to-end, including temporal membership, Alfa administration, Beta scoped read-only access, optimistic concurrency, operational-memory events, IndexedDB cache/outbox, and a restrained responsive UI.

**Architecture:** Keep `cortex_*` and `commit_seq` authoritative. The backend sync service becomes an idempotency/bookkeeping orchestrator that delegates domain work to operation handlers. Teams use JDBC services over additive MySQL tables, authorize at service boundaries through `CurrentUserService`, and publish structured objects/relations/events through `CortexOperationalMemoryService`. The web app raises IndexedDB to v10, adds team stores and operation-specific reconciliation, reads cached authorized data first, and refreshes from the scoped API when online.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JdbcTemplate, Flyway/MySQL 8.4, JUnit 5/AssertJ/Mockito/MockMvc; React 19, TypeScript 6, Vite 8, IndexedDB/idb, Vitest, PWA.

---

## Non-negotiable invariants

- Never edit V1-V26; add V27 only.
- Keep `cortex_evento_operacional.commit_seq` as the sole sync cursor.
- Preserve the existing RDO response contract and all current RDO sync tests.
- Validate worksite scope before returning a team, member, function assignment, or history record.
- Team membership never grants worksite authorization implicitly.
- Role/access changes remain online-only and outside the generic outbox.
- All update/close/archive operations require `baseVersao` and return an explicit conflict on mismatch.
- No fake teams, members, roles, activity, or timestamps.
- Use JDK 21 for every Maven command.
- Commit after each green task; do not push.

## Task 1: Add the temporal team schema and sync operation vocabulary

**Files:**

- Create: `apps/api/src/main/resources/db/migration/V27__create_equipes_and_extend_sync.sql`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeMigrationTest.java`

**Step 1: Write the failing migration contract test**

Create `EquipeMigrationTest` that reads V27 and asserts the real invariants, including:

```java
assertThat(sql).contains("CREATE TABLE funcao_operacional");
assertThat(sql).contains("CREATE TABLE equipe");
assertThat(sql).contains("CREATE TABLE equipe_obra");
assertThat(sql).contains("CREATE TABLE equipe_membro");
assertThat(sql).contains("versao_linha BIGINT NOT NULL DEFAULT 0");
assertThat(sql).contains("DROP CHECK chk_sync_mutacao_operacao");
assertThat(sql).contains("'CRIAR_EQUIPE'");
assertThat(sql).contains("'ENCERRAR_MEMBRO_EQUIPE'");
assertThat(sql).doesNotContain("ON DELETE CASCADE");
```

Run:

```bash
cd apps/api
env JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=EquipeMigrationTest test
```

Expected: FAIL because V27 does not exist.

**Step 2: Implement V27**

Create four InnoDB/utf8mb4 tables. Foreign-key UUID columns must use the same
`utf8mb4_unicode_ci` definition as the existing `obra` and `colaborador` IDs;
do not copy the ASCII definition used only by the sync/event identifiers:

- `funcao_operacional`: `id`, unique `codigo`, `nome`, `descricao`, `ativo`, `ordem_exibicao`, author/timestamps, `versao_linha`.
- `equipe`: `id`, `obra_principal_id`, `nome`, `descricao`, `status` (`ATIVA`, `INATIVA`, `ARQUIVADA`), validity, authorship/timestamps, `arquivada_em`, `versao_linha`.
- `equipe_obra`: `id`, team/worksite, temporal status (`ATIVO`, `ENCERRADO`), `inicio_em`, `fim_em`, reason/authorship/timestamps, `versao_linha`; unique `(equipe_id, obra_id, inicio_em)` plus current-state indexes.
- `equipe_membro`: `id`, team/collaborator/function, `responsavel`, temporal status (`ATIVO`, `ENCERRADO`), dates, reason/authorship/timestamps, `versao_linha`; unique `(equipe_id, colaborador_id, inicio_em)` plus current-state indexes.

Use restrictive foreign keys to `obra`, `colaborador`, and `funcao_operacional`. Do not seed operational roles. Drop and recreate `chk_sync_mutacao_operacao`, preserving every existing value and adding:

```text
CRIAR_EQUIPE
ATUALIZAR_EQUIPE
ARQUIVAR_EQUIPE
ADICIONAR_MEMBRO_EQUIPE
ATUALIZAR_MEMBRO_EQUIPE
ENCERRAR_MEMBRO_EQUIPE
```

**Step 3: Run the focused test and schema lint**

Run the focused Maven test, then:

```bash
git diff --check
```

Expected: PASS and no whitespace errors.

**Step 4: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V27__create_equipes_and_extend_sync.sql apps/api/src/test/java/com/projeto/cortex/equipes/EquipeMigrationTest.java
git commit -m "feat(api): add temporal team schema"
```

## Task 2: Extract extensible backend sync handlers without changing RDO behavior

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/sync/SyncMutationHandler.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/SyncMutationApplied.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/RdoSyncMutationHandler.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncPullScopeTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncServicePullVersionTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncServiceAuthorizationTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncServiceSecurityTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/sync/SyncMutationHandlerRegistryTest.java`

**Step 1: Write failing handler-registry tests**

Test that:

- a handler is selected by exact operation;
- duplicate operation ownership fails at construction;
- unsupported operations return the same Portuguese 400 contract;
- RDO create/update/send delegate to the same services and policies as before;
- replay still returns the stored result rather than calling the handler again.

The handler contract is:

```java
public interface SyncMutationHandler {
    Set<String> operations();
    boolean requiresBaseVersion(String operation);
    SyncMutationApplied apply(SyncPushRequest.MutacaoCliente mutation);
}
```

The normalized result is:

```java
public record SyncMutationApplied(
        String entityType,
        String entityId,
        long entityVersion,
        long commitSeq,
        JsonNode result
) {}
```

Run all `com.projeto.cortex.sync` tests. Expected: FAIL because the contracts do not exist.

**Step 2: Move RDO-specific behavior into `RdoSyncMutationHandler`**

Move payload mapping, worksite/RDO policy checks, RDO existence idempotency, and service calls out of `SyncService`. Keep the current behavior byte-for-byte at the API boundary. The RDO handler adds the canonical `versaoEntidade` to its sanitized result and resolves the latest `commit_seq` from `cortex_estado_entidade`.

**Step 3: Make `SyncService` an orchestrator**

Inject `List<SyncMutationHandler>`, build an immutable operation registry, and replace `OPERACOES_SUPORTADAS`, `aplicarOperacao`, RDO-only base-version logic, and RDO-only result conversion with handler delegation. Keep:

- device ownership checks;
- pending record before application;
- terminal bookkeeping in a new transaction after failure/conflict;
- uniqueness by device/client mutation;
- replay semantics;
- batch size and pull/ACK behavior.

Reject `entidadeTipo` that does not match the applied result. Use the handler’s `requiresBaseVersion` decision and compare against `cortex_estado_entidade` before mutation.

**Step 4: Update tests to construct the orchestrator with handlers**

Tests that only exercise pull may pass `List.of()`; RDO tests construct a real `RdoSyncMutationHandler` with mocks. Do not weaken authorization assertions.

**Step 5: Verify**

Run:

```bash
cd apps/api
env JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest='com.projeto.cortex.sync.*' test
```

Expected: all sync tests green.

**Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/sync apps/api/src/test/java/com/projeto/cortex/sync
git commit -m "refactor(sync): delegate mutations to domain handlers"
```

## Task 3: Implement team queries, commands, and operational-memory publishing

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeStatus.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeCreateRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeUpdateRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeMemberRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/FuncaoOperacionalRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeMemberResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/FuncaoOperacionalResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipePageResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeMemoryPublisher.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeMemoryPublisherTest.java`

**Step 1: Write failing service tests**

Cover these cases with `JdbcTemplate`, `CurrentUserService`, and publisher mocks:

- Alfa creates a team and its initial `equipe_obra` link atomically.
- A client-provided UUID is preserved, making create replay-safe.
- Beta listing is constrained to `allowedObraIds`; empty scope returns no rows without a global query.
- Reading by ID calls `requireWorksiteAccess` before returning members.
- Adding a member requires Alfa, an active function, an existing collaborator, and current team/worksite access.
- Duplicate active membership returns the existing participation rather than inserting another.
- Updating/archive/closing with stale `baseVersao` raises an explicit conflict and writes nothing.
- Closing a member sets `fim_em` and keeps the row.
- Archiving a team ends active worksite and member links; it never deletes.
- Team membership does not call `VinculoColaboradorObraService` unless `concederAcessoObra=true` was explicitly requested.

**Step 2: Implement DTO validation and scoped SQL**

Normalize text at the boundary, enforce name length and chronological validity, cap page size at 100, and return records rather than JDBC maps. Team list supports `obraId`, text, function, status, validity instant, `page`, and `size` with deterministic `(nome, id)` ordering.

For Beta, intersect any requested worksite with `allowedObraIds`. For Alfa, `allowedObraIds` is empty and means global scope. Never interpolate IDs or filters into SQL.

**Step 3: Implement optimistic commands**

Every existing-row update uses SQL shaped as:

```sql
UPDATE equipe
SET nome = ?, descricao = ?, versao_linha = versao_linha + 1
WHERE id = ? AND versao_linha = ?
```

When update count is zero, distinguish not-found from stale version and return 404 or conflict. Run commands in `@Transactional` methods and publish memory within the same transaction.

**Step 4: Publish the domain into operational memory**

Register objects `EQUIPE`, `PARTICIPACAO_EQUIPE`, and `FUNCAO_OPERACIONAL`; active relations `ATUA_EM`, `MEMBRO_DE`, `EXERCE_FUNCAO`, and `LIDERA`; and detailed events with:

```text
schemaVersion, actorId, operation, beforeState, afterState,
changedFields, baseVersion, entityVersion, reason, worksiteId
```

End relations when temporal links close. Use the worksite ID in every event so Beta pull filtering remains effective.

**Step 5: Run focused tests and commit**

Run `./mvnw -Dtest='com.projeto.cortex.equipes.EquipeServiceTest,com.projeto.cortex.equipes.EquipeMemoryPublisherTest' test` with JDK 21.

Commit:

```bash
git add apps/api/src/main/java/com/projeto/cortex/equipes apps/api/src/test/java/com/projeto/cortex/equipes
git commit -m "feat(api): implement scoped team domain"
```

## Task 4: Expose team, function, membership, history, and access-role APIs

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/FuncaoOperacionalController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeHistoryResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeHistoryService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/colaboradores/PapelAcessoUpdateRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/colaboradores/PapelAcessoUpdateResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/colaboradores/PapelAcessoService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/ColaboradorController.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeControllerMockMvcTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeHistoryServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/colaboradores/PapelAcessoServiceTest.java`

**Step 1: Write failing HTTP authorization tests**

Assert:

- Alfa gets 200 for create/update/archive/function/member commands.
- Beta gets 403 and the service is never called for every command endpoint.
- Scoped Beta gets 200 for list/detail/history in an authorized worksite.
- Out-of-scope detail and history return 403 before response serialization.
- Invalid pagination/temporal fields return 400 with Portuguese messages.

**Step 2: Implement endpoints**

Expose:

```text
GET    /api/equipes
GET    /api/equipes/{id}
GET    /api/equipes/{id}/historico
POST   /api/equipes
PUT    /api/equipes/{id}
POST   /api/equipes/{id}/arquivar
POST   /api/equipes/{id}/membros
PUT    /api/equipes/{id}/membros/{participacaoId}
POST   /api/equipes/{id}/membros/{participacaoId}/encerrar
GET    /api/funcoes-operacionais
POST   /api/funcoes-operacionais
PUT    /api/funcoes-operacionais/{id}
PUT    /api/colaboradores/{id}/papel-acesso
```

Use `@Valid`, response records, and existing Spring error behavior. `EquipeHistoryService` reads `cortex_evento_operacional` by `tipo_entidade IN ('EQUIPE','PARTICIPACAO_EQUIPE')`, team/related IDs, and authorized worksite.

**Step 3: Implement access-role safety**

`PapelAcessoService` requires Alfa, a nonblank reason, current role in the request, and an exact current-row version. It rejects self-demotion from ALFA to BETA, records before/after and reason in operational memory, and never exposes CPF hash. This endpoint is online-only; do not add a sync handler.

**Step 4: Verify and commit**

Run the focused controller/service tests with JDK 21, then commit:

```bash
git add apps/api/src/main/java/com/projeto/cortex/equipes apps/api/src/main/java/com/projeto/cortex/colaboradores apps/api/src/test/java/com/projeto/cortex/equipes apps/api/src/test/java/com/projeto/cortex/colaboradores
git commit -m "feat(api): expose team administration and history"
```

## Task 5: Add team mutation handlers and prove offline idempotency/conflicts

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeSyncMutationHandler.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeSyncMutationHandlerTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/equipes/EquipeSyncMysqlIntegrationTest.java`

**Step 1: Write failing handler tests**

Cover all six V27 operations. Each operation must:

- require an authenticated Alfa session;
- map a typed payload;
- validate worksite access in the service;
- use the client UUID for creates;
- require `baseVersao` except creates/add-member creates;
- return `EQUIPE` or `PARTICIPACAO_EQUIPE`, the canonical memory version, latest `commit_seq`, and sanitized DTO.

**Step 2: Implement the handler**

Do not duplicate business rules; delegate to `EquipeService`. Return no CPF, JWT, or unrestricted collaborator payload.

**Step 3: Prove replay and conflict on MySQL**

The integration test is enabled only when `CORTEX_MYSQL_ROOT_PASSWORD` exists. Against a migrated disposable database, prove:

1. the same `(deviceId, clientMutationId)` creates exactly one team;
2. a new mutation ID with the same team UUID returns the existing team without a second row;
3. concurrent updates with the same base version produce one apply and one conflict;
4. the applied mutation points to the team event’s `commit_seq`;
5. a Beta mutation returns 403/ERRO and changes no row.

**Step 4: Verify and commit**

Run focused tests; if MySQL credentials are absent, keep the integration test skipped with an explicit assumption and run it during the final MySQL gate.

Commit:

```bash
git add apps/api/src/main/java/com/projeto/cortex/equipes/EquipeSyncMutationHandler.java apps/api/src/test/java/com/projeto/cortex/equipes
git commit -m "feat(sync): support idempotent team mutations"
```

## Task 6: Generalize the web outbox and add persisted backoff

**Files:**

- Modify: `apps/web/src/lib/db/db.types.ts`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Modify: `apps/web/src/lib/db/outboxRepository.ts`
- Modify: `apps/web/src/lib/sync/sync.types.ts`
- Modify: `apps/web/src/lib/sync/pushOutbox.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.ts`
- Create: `apps/web/src/lib/sync/retryPolicy.ts`
- Create: `apps/web/src/lib/sync/retryPolicy.test.ts`
- Create: `apps/web/src/lib/sync/syncResultHandlers.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.test.ts`

**Step 1: Write failing pure retry tests**

Use injected timestamps/random values and assert:

```ts
expect(nextRetryDelayMs(0, 0)).toBe(1_000);
expect(nextRetryDelayMs(1, 0)).toBe(2_000);
expect(nextRetryDelayMs(8, 0)).toBeLessThanOrEqual(300_000);
expect(isRetryDue({ proximaTentativaEm: future }, now)).toBe(false);
```

Also test that permanent server errors stay `ERROR`, network/5xx failures return to `PENDING` with `proximaTentativaEm`, and a conflict stays `CONFLICT`.

**Step 2: Extend types without weakening them**

Add entity types `EQUIPE` and `PARTICIPACAO_EQUIPE`, the six team operations, and `proximaTentativaEm` to `OutboxMutationRecord`. Keep discriminated helpers so RDO-only recovery logic cannot run for a team mutation.

**Step 3: Preserve IndexedDB v9 until the team stores land**

The outbox store schema remains compatible and adding the optional
`proximaTentativaEm` field needs no IndexedDB schema change. Existing rows are
treated as due when the field is absent. Do not bump the database version in
this task: Task 7 creates all team stores and performs the single v9 to v10
upgrade atomically, so no installed client can reach v10 without the stores.

**Step 4: Add operation-specific result handlers**

Extract the current RDO apply/conflict logic behind a handler registry and add team handlers later. Unknown operations must leave the mutation in `ERROR` with a visible diagnostic; never mark them synced.

**Step 5: Verify and commit**

Run:

```bash
cd apps/web
npm test -- src/lib/sync/retryPolicy.test.ts src/lib/sync/syncStorage.test.ts
npm run build
```

Commit:

```bash
git add apps/web/src/lib/db apps/web/src/lib/sync
git commit -m "refactor(web): extend outbox with persisted retry policy"
```

## Task 7: Add the local team read model and offline commands

**Files:**

- Modify: `apps/web/src/lib/db/db.types.ts`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Create: `apps/web/src/lib/db/equipeRepository.ts`
- Create: `apps/web/src/lib/db/equipeRepository.test.ts`
- Create: `apps/web/src/features/equipes/equipe.types.ts`
- Create: `apps/web/src/features/equipes/equipeApi.ts`
- Create: `apps/web/src/features/equipes/equipeApi.test.ts`
- Create: `apps/web/src/features/equipes/equipeLocalService.ts`
- Create: `apps/web/src/features/equipes/equipeLocalService.test.ts`
- Modify: `apps/web/src/lib/sync/syncResultHandlers.ts`
- Modify: `apps/web/src/lib/sync/syncStorage.ts`

**Step 1: Write failing repository and command tests**

Using the project’s fake IndexedDB setup, prove:

- teams are listed by authorized cached worksite and deterministic name;
- member/function records can be replaced from a server detail atomically;
- create shows a local team immediately and queues exactly one `CRIAR_EQUIPE` mutation;
- update/archive/member commands use the current `versaoEntidade` as `baseVersao`;
- a team server result updates the right store and removes only its own mutation;
- a conflict preserves the local draft, sets `CONFLICT`, and stores server version.

**Step 2: Upgrade once from v9 and add all v10 stores**

Create:

```text
equipes              indexes by-obra-id, by-status, by-updated-at
equipe_membros       indexes by-equipe-id, by-colaborador-id, by-status
funcoes_operacionais indexes by-ativo, by-ordem
```

Records include local sync status, entity version, server payload, and update timestamps. Do not cache out-of-scope records after logout: add repository cleanup called when the authenticated user changes in the sync registration flow.

**Step 3: Implement API and local service**

`equipeApi.ts` uses `apiClient`, typed page/detail DTOs, query encoding, and Portuguese failures. `equipeLocalService.ts` accepts an injected online state:

- Alfa offline commands update local records and enqueue stable UUID mutations.
- Beta commands throw before changing IndexedDB.
- online refresh replaces only records returned for the current authorized scope.
- role changes always call the online endpoint and fail clearly when offline.

**Step 4: Reconcile pull events**

For team/participation events, apply the event’s sanitized `afterState` when complete; otherwise fetch detail on the next online refresh. Always advance the processed-event cursor atomically, even when a deleted/archived record is represented by terminal state.

**Step 5: Verify and commit**

Run focused Vitest and the full TypeScript build, then commit:

```bash
git add apps/web/src/lib/db apps/web/src/lib/sync apps/web/src/features/equipes
git commit -m "feat(web): persist teams and offline commands"
```

## Task 8: Build the restrained Equipes UI and wire real team consumers

**Prerequisite skill:** Read and apply `frontend-design:frontend-design` before editing UI files.

**Files:**

- Create: `apps/web/src/features/equipes/EquipesPage.tsx`
- Create: `apps/web/src/features/equipes/EquipesPage.css`
- Create: `apps/web/src/features/equipes/equipeViewModel.ts`
- Create: `apps/web/src/features/equipes/equipeViewModel.test.ts`
- Modify: `apps/web/src/App.tsx`
- Modify: `apps/web/src/components/shell/CortexShell.tsx`
- Modify: `apps/web/src/features/tarefas/TarefasPage.tsx`
- Modify: `apps/web/src/features/home/teamFromRdo.ts`
- Modify: `apps/web/src/features/home/teamFromRdo.test.ts`

**Step 1: Write failing view-model tests**

Test search normalization, status/function/worksite filters, initials, active-member counts, Beta action visibility, offline/conflict labels, and temporal date formatting. Keep UI logic outside React where possible because the repo has no component-testing dependency.

**Step 2: Lazy-load `/equipes`**

Use `React.lazy`/`Suspense` in `App.tsx`. Add `equipes` to `ShellActiveItem`; make the existing sidebar button navigate and expose active/keyboard state. The route chunk must be separate in the production build.

**Step 3: Implement list/detail administration**

Use the current shell, Poppins, teal/yellow accents, flat surfaces, and existing spacing/radii. Deliver:

- search and worksite/function/status filters;
- real empty/loading/error/offline states;
- team rows with initials, name, worksite, active-member count, function summary, and conflict/sync badge;
- detail drawer/panel with active and historical memberships;
- Alfa create/edit/archive, member add/change/end, and function administration;
- Beta read-only presentation with no misleading disabled admin controls;
- mobile single-column drill-in and safe-area spacing.

No glow, glass, fake avatar photos, fake recent activity, or synthetic skeleton content.

**Step 4: Remove runtime derivation of teams from RDO allocations**

`TarefasPage` and Home use the team repository/API as their source. If no real team exists for the worksite, show “Nenhuma equipe cadastrada” rather than converting an RDO trade/function string into a team. Keep the old pure utility only if still needed to label historical RDO allocation facts; rename its visible semantics accordingly.

**Step 5: Verify accessibility, responsive CSS, tests, and build**

Run:

```bash
cd apps/web
npm test
npm run lint
npm run build
```

Expected: all tests/lint/build pass and the build output contains a distinct Equipes chunk.

**Step 6: Commit**

```bash
git add apps/web/src/App.tsx apps/web/src/components/shell/CortexShell.tsx apps/web/src/features/equipes apps/web/src/features/tarefas apps/web/src/features/home
git commit -m "feat(web): deliver responsive team workspace"
```

## Task 9: Validate the complete increment against real MySQL and current regressions

**Files:**

- Create: `docs/superpowers/reports/2026-07-15-cortex-foundation-teams-verification.md`
- Modify only if evidence exposes a defect: files owned by Tasks 1-8, with a new failing regression test first.

**Step 1: Run the complete automated baseline**

```bash
cd apps/api
env JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw clean test
env JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw -DskipTests package
cd ../web
npm test
npm run lint
npm run build
```

Expected: no regression from 399 API tests/6 skips and 141 web tests; new totals are higher.

**Step 2: Prove migrations on MySQL 8.4**

With `compose.local.yml` MySQL on port 3307 and `CORTEX_MYSQL_ROOT_PASSWORD` set, run the integration suite twice:

- fresh empty schema V1→V27;
- schema restored at V26, then upgraded to V27.

Assert Flyway checksums for V1-V26 are unchanged, V27 applies once, all FKs/checks/indexes exist, and sync/team integration tests pass.

**Step 3: Run an authenticated API smoke matrix**

Using real Alfa and Beta tokens in the disposable environment, capture status/body facts for:

- Alfa create/function/member/update/archive;
- Beta scoped list/detail/history;
- Beta command 403;
- Beta IDOR against another worksite 403;
- idempotent replay and stale-version conflict;
- pull includes the authorized team events and excludes the other worksite.

Do not use production accounts or the real vault/provider data.

**Step 4: Run browser verification**

In Edge/Chromium against the local API/MySQL:

- desktop, tablet, and mobile layouts;
- keyboard navigation and focus visibility;
- Alfa administration;
- Beta read-only UI;
- actual browser offline create/update, reconnect, reconciliation, and conflict;
- no console errors and no duplicate rows.

Capture screenshots into the report’s evidence directory if the repo already has one; otherwise record exact commands, dimensions, statuses, and observations without adding binary files to Git.

**Step 5: Write the verification report**

Record exact commands, timestamps, pass/fail counts, skipped MySQL tests (if any), screenshots/browser evidence, open limitations, bundle sizes, and the next increment boundary. Never claim checks that were not performed.

**Step 6: Final diff review and commit**

Run:

```bash
git diff --check
git status --short
git log --oneline --decorate -12
```

Commit the evidence and any test-led corrections:

```bash
git add docs/superpowers/reports/2026-07-15-cortex-foundation-teams-verification.md
git commit -m "docs: verify team vertical slice"
```

## Increment completion gate

Do not start Mensagens until all of the following are true:

- Existing RDO sync tests still pass through the handler abstraction.
- Teams persist and retain temporal history in MySQL.
- Alfa commands and Beta scoped reads are enforced by backend tests and real smoke requests.
- Offline team mutations are idempotent, back off, reconcile, and surface conflicts.
- Every team/member change yields scoped operational-memory events and relations.
- Home/Tarefas no longer present RDO-derived names as real teams.
- Web tests/lint/build and API tests/package are green on current code.
- Verification evidence names any skipped live check honestly.

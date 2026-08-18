# Local Academy and Zeladoria Integration Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Make the local Córtex API consume complete, read-only, TLS-verified Academy and Zeladoria snapshots, treat the current source state as authoritative, preserve historical references, and run both local schedulers safely.

**Architecture:** Keep source reads outside the Córtex transaction, but require a complete validated repeatable-read snapshot before applying it. Serialize each connector with a PostgreSQL advisory lock; apply each snapshot atomically in PostgreSQL. Secrets and pinned truststores are mounted as files, and hosted schedulers remain disabled.

**Tech Stack:** Java 21, Spring Boot 3, JDBC, PostgreSQL 18, MySQL Connector/J, Flyway, JUnit 5, AssertJ, Mockito, Testcontainers, Docker Compose, Bash.

---

## Safety boundary

- [ ] Never execute mutating SQL against Academy or Zeladoria.
- [ ] Never automate or modify MySQL Workbench in this plan.
- [ ] Never print, commit, log, or pass a database password on a command line.
- [ ] Do not enable either scheduler until the one-shot imports and domain checks pass.
- [ ] Do not enable the hosted/Render schedulers.

## Task 1: Generalize the production MySQL source security boundary

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/PinnedMysqlSourceTlsPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyProductionTlsPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/integracoes/ZeladoriaSourceAdapter.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/test/java/com/projeto/cortex/integracoes/ZeladoriaProductionSecurityTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyProductionTlsPolicyTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/integracoes/ExternalSourceAdapterTest.java`

1. Write failing tests proving that production Zeladoria:
   - rejects inline password even when its scheduler is disabled;
   - requires a readable password file when enabled;
   - accepts `VERIFY_IDENTITY` without a custom truststore;
   - accepts `VERIFY_CA` only with the exact fixed Zeladoria PKCS12 path, one trusted leaf, and no system fallback;
   - redacts URL, username, password and file paths from every public failure.
2. Run the focused tests and record the expected RED caused by the current inline-only Zeladoria adapter.
3. Extract the already-tested Academy TLS parser/store validator into `PinnedMysqlSourceTlsPolicy`, parameterized only by safe connector label and exact fixed path.
4. Keep thin Academy and Zeladoria policies so public messages remain source-specific.
5. Add Zeladoria `password-file`, runtime-mode and scheduler-enabled inputs matching the Academy secret contract.
6. Resolve the file only when opening a connection; reject inline+file and empty/unreadable files safely.
7. Run focused tests GREEN, then run the existing Academy security tests to prove no regression.
8. Commit: `feat(integrations): secure Zeladoria source credentials and TLS`.

## Task 2: Read a complete Zeladoria snapshot

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/ZeladoriaAssetSnapshot.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/integracoes/ZeladoriaSourceAdapter.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/integracoes/ZeladoriaSourceAdapterSnapshotTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/integracoes/ZeladoriaSourceAdapterMysqlSnapshotIT.java`

1. Write a unit-level JDBC seam test whose second page proves that a complete snapshot is read in ascending source ID order inside one read-only `REPEATABLE_READ` transaction.
2. Add failure cases for rollback, non-advancing keyset and partial-page failure. The observable result must be one redacted snapshot error, never a partial list.
3. Run the focused test and confirm RED because only the capped `fetchAssets(maxRows)` API exists.
4. Add `fetchCompleteSnapshot(pageSize)` returning immutable `ZeladoriaAssetSnapshot.complete(...)` and paginate by source `id` without the old 10,000-row ceiling.
5. Keep `testConnection()` read-only and remove production use of the capped API.
6. Add a real MySQL Testcontainers IT proving two pages, snapshot consistency and zero source mutations.
7. Run focused tests GREEN.
8. Commit: `feat(integrations): read complete Zeladoria snapshots`.

## Task 3: Make Zeladoria application validated, serialized and atomic

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/SourceImportRunLock.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/AcademyImportRunLock.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/ColaboradorImportService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/assets/AssetImportService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/assets/AssetImportServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/assets/PostgresqlZeladoriaImportAtomicityIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlAcademyImportAtomicityIT.java`

1. Write RED tests proving:
   - null/incomplete snapshot makes zero domain writes;
   - blank or duplicate source IDs make zero domain writes;
   - a complete snapshot upserts present assets and logically deactivates missing assets;
   - a reappearing asset is reactivated without changing its stable ID;
   - absent assets remain addressable by historical foreign keys;
   - a late checkpoint/memory failure rolls back assets, checkpoint and success state;
   - two concurrent imports of the same connector do not apply together.
2. Run focused unit and PostgreSQL ITs; confirm expected REDs from lack of validation, deactivation, transaction and lock.
3. Generalize the Academy advisory lock into a connector-scoped `SourceImportRunLock`, retaining safe failures and a dedicated connection.
4. Prepare the entire Zeladoria snapshot before applying it. Reject ambiguity before the PostgreSQL transaction.
5. Apply all upserts, soft-deactivations, memory evidence, checkpoint and successful run completion in one `REQUIRES_NEW` transaction.
6. Persist a safe FAILED run separately after rollback.
7. A row absent from the complete Zeladoria snapshot is an upstream deletion: set `active=false`, set `deleted_at=CURRENT_TIMESTAMP(6)` and increment `row_version` only on a real active-to-inactive transition. Never delete the Córtex row.
8. A present row clears a historical `deleted_at`, sets `active=true`, updates `last_seen_at`, and preserves the stable `id`.
9. Run focused tests GREEN, including existing Academy atomicity ITs.
10. Commit: `feat(integrations): atomically reconcile Zeladoria assets`.

## Task 4: Reflect current Academy authority explicitly

**Files:**

- Modify: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademySourceAdapter.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademySourceAdapterMysqlSnapshotIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlAcademyImportAtomicityIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/PostgresqlAcademyCpfLoginIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/PostgresqlRdoCreationContextIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/obras/rateio/PostgresqlRateioMaoDeObraIT.java`

1. Add a RED/characterization journey with two snapshots: initial active workforce, then current source with one explicitly inactive, one missing person and an updated `usuarios.funcao`.
2. Prove the current complete snapshot contract:
   - keeps synchronizing all other fields when the optional source column `usuarios.funcao` is unavailable;
   - leaves a new collaborator's function blank and preserves a manual Córtex value when the source function is null or blank;
   - updates the canonical function from the current Academy row;
   - exposes that function in RDO creation context and workforce rateio;
   - preserves the missing row and its login eligibility;
   - deactivates the explicitly inactive row and revokes only that Academy CPF login;
   - keeps the collaborator and prior RDO references;
   - keeps an active person's password/passkey identity usable;
   - does not assign a released CPF to the wrong person.
3. If the tests reveal a gap, implement only the missing behavior through the existing transactional import. Otherwise retain the tests as proof of the approved authority contract.
4. Run the three focused PostgreSQL ITs GREEN.
5. Commit: `test(integrations): prove current Academy authority`.

## Task 5: Add symmetric staleness and safe administration status

**Files:**

- Modify: `apps/api/src/main/java/com/projeto/cortex/integracoes/IntegracaoAdminService.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/test/java/com/projeto/cortex/integracoes/IntegracaoAdminServiceTest.java`

1. Write failing tests that stale enabled Zeladoria becomes `ATRASADA`, fresh stays `SUCCESS`, disabled scheduling never invents delay, and persisted source/driver details are redacted for both connectors.
2. Confirm RED because the current logic is Academy-only.
3. Introduce per-source enabled/max-age settings and a connector-neutral stale-state function.
4. Preserve runtime readiness independence from source freshness.
5. Run tests GREEN.
6. Commit: `feat(integrations): report stale local source syncs`.

## Task 6: Wire file secrets, truststores and local-only schedules

**Files:**

- Modify: `deploy/production/compose.yml`
- Modify: `deploy/production/README.md`
- Modify: `scripts/deploy/prepare-local-production.sh`
- Modify: `scripts/security/test-local-compose-security.sh`
- Modify: `scripts/security/test-production-publication.sh`
- Modify: `apps/api/src/test/java/com/projeto/cortex/security/ProductionSecurityContractTest.java`
- Modify: `.env.production.example` if present

1. Extend the executable security scripts first and run them RED. They must prove:
   - both passwords are mounted as files under `/run/secrets`;
   - both truststores are mounted read-only under fixed paths;
   - no raw source password appears in rendered environment;
   - Academy local config disables inference of deactivation from missing rows;
   - both local schedules default false;
   - hosted configuration keeps both schedules false.
2. Update compose and preparation scripts to copy secret files without printing contents and to reject symlinks, empty files and permissive modes.
3. Add fixed-path truststore secrets for both sources.
4. Document exact JDBC query properties without embedding the truststore password when the store is intentionally unprotected; if protected, the password must be a separate secret and percent-encoded by the operator tool, never logged.
5. Run all security scripts GREEN.
6. Commit: `ops(integrations): wire pinned local source secrets`.

## Task 7: Full verification and publication gate

1. From the repository root run:

   ```bash
   (cd apps/api && ./mvnw test)
   (cd apps/api && ./mvnw -Ppostgresql-it verify)
   (cd apps/api && ./mvnw -Pmysql-it verify)
   bash scripts/security/test-local-compose-security.sh
   bash scripts/security/test-production-publication.sh
   bash scripts/security/test-hosted-deployment-contract.sh
   git diff --check origin/develop...HEAD
   git status --short
   ```

2. Review the branch diff for secret leakage, unsafe source SQL and accidental Workbench artifacts.
3. Open a PR to `develop`, wait for every required CI job, and merge using the repository's current squash convention.
4. Confirm `origin/develop` contains the merge and obtain the exact release SHA.

## Task 8: Controlled server rollout

This task has an explicit human confirmation boundary before reading the saved credentials from macOS Keychain or installing them on the server.

1. Confirm the server still runs the expected pre-rollout SHA and both schedulers are false.
2. Ask for action-time confirmation to unlock the two existing Workbench credentials. If macOS presents an authorization prompt, hand control to the user. Never show the values.
3. Install password files and one-leaf PKCS12 truststores with owner-only permissions.
4. Deploy the exact merged image with both schedulers still false.
5. Validate `testConnection`/equivalent read-only probes and confirm source counts are observed, not hardcoded.
6. Run one manual Academy sync and one manual Zeladoria sync.
7. Verify:
   - Academy run is SUCCESS and current deactivations are represented;
   - an active Academy user can log in and a deactivated user cannot;
   - Zeladoria run is SUCCESS and equipment is available for new RDO context;
   - historical RDOs referring to old collaborators/assets still render/export;
   - offline RDO creation and later replay still work;
   - PDOR/revenue behavior is unchanged by source refresh alone.
8. Keep Academy missing-row deactivation disabled and enable both schedules at five minutes.
9. Observe at least two automatic cycles, with no overlapping run, no spurious updates and no secret-bearing error.
10. Keep Render/hosted schedules false and record the rollback image/SHA.

## Completion boundary

- [ ] Source MySQLs received only read-only transactions.
- [ ] Workbench was not changed.
- [ ] Code and integration tests passed.
- [ ] CI passed for the exact merged SHA.
- [ ] Exact SHA is served by the local API and PWA.
- [ ] Current Academy deactivations and available `usuarios.funcao` updates are reflected without history loss.
- [ ] Current Academy function appears in RDO creation context and workforce rateio.
- [ ] Zeladoria equipment refresh works without history loss.
- [ ] Two automatic local cycles passed.
- [ ] Hosted schedulers remain disabled.

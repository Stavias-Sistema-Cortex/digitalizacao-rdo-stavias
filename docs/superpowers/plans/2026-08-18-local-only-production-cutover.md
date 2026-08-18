# Córtex Local-Only Production Cutover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the canonical Córtex runtime, PostgreSQL database, and object storage to the Stavias server while keeping Neon, Render, Cloudflare Pages, and R2 intact as rollback systems until local acceptance and the retention window finish.

**Architecture:** Keep `https://cortex.portalstavias.com.br` on the existing Locaweb DNS and Apache TLS entry point. Run the immutable API and PWA images, PostgreSQL 18, and persistent object storage on the Stavias server; rehearse database and object restoration beside the current canary, then perform a bounded write freeze and atomic Apache upstream switch. Remote services remain untouched throughout this plan and are only eligible for a later, separately approved decommission.

**Tech Stack:** Debian 13, Docker Compose v2, Apache 2.4, GHCR, PostgreSQL 18, Spring Boot/Java 21, React/Vite PWA, Bash, pg_dump/pg_restore, S3-compatible Cloudflare R2.

**Spec:** `docs/production-runbook.md`

## Global Constraints

- Do not delete, disable, mutate, or rotate Neon, Render, Cloudflare Pages, R2, or their credentials during this plan.
- Do not alter Academy or Zeladoria MySQL data; both connectors remain TLS-pinned and SELECT-only.
- Do not log, echo, commit, or copy secret values into shell history, Git, CI artifacts, or evidence files.
- Preserve `https://cortex.portalstavias.com.br` as the exact browser origin and WebAuthn RP ID.
- Every deployed API and PWA image must resolve to the same full Git SHA and have a valid GitHub provenance attestation.
- The local PostgreSQL target is PostgreSQL 18 database `StaviasCortex` with separate admin, migrator, and runtime roles.
- The local object target is a persistent volume and every available object must be verified by storage key, byte count, and SHA-256 before cutover.
- The final cutover requires a write freeze, a last database dump, a last object delta, and a tested rollback to the pre-cutover Apache upstream.
- Do not clear browser site data; pending offline work must survive the migration.
- A green unit/CI gate is not live acceptance. Record code, image, database, storage, server, and browser evidence separately.

---

### Task 1: Capture a redacted, exact pre-cutover state

**Files:**
- Create: `scripts/deploy/capture-local-cutover-state.sh`
- Create: `scripts/deploy/test-capture-local-cutover-state.sh`
- Modify: `docs/production-runbook.md`

**Interfaces:**
- Consumes: `CORTEX_BASE_URL`, `CORTEX_EXPECTED_RELEASE_SHA`, Docker Compose project `cortex-production`, and the server runtime directory.
- Produces: a mode-600 JSON evidence file containing only revision, readiness state, image digests, container health, volume names, redacted database host classification, and UTC timestamps.

- [x] **Step 1: Write the failing shell contract test**

Create fixtures for healthy matching images, mismatched API/PWA revisions, a raw secret-shaped value, non-READY health, and an unsafe evidence path. Require exact-SHA matching, redaction, mode `600`, and atomic rename from a temporary file.

- [x] **Step 2: Run the contract test and confirm RED**

Run:

```bash
bash scripts/deploy/test-capture-local-cutover-state.sh
```

Expected: non-zero because `capture-local-cutover-state.sh` does not exist.

- [x] **Step 3: Implement the read-only capture command**

The script must use `set -euo pipefail`, `umask 077`, bounded `curl`, `docker inspect`, and `docker compose ps`. It must classify the JDBC host as `LOCAL`, `NEON`, or `OTHER` without emitting the hostname, username, password, URL query, cookie, CPF, object name, or source record. Reject symlink destinations and evidence directories writable by group or others.

- [x] **Step 4: Run the focused GREEN gate**

Run:

```bash
bash scripts/deploy/test-capture-local-cutover-state.sh
bash scripts/security/scan-cortex-secrets.sh
```

Expected: both exit `0`.

- [x] **Step 5: Document the evidence command and commit**

```bash
git add scripts/deploy/capture-local-cutover-state.sh \
  scripts/deploy/test-capture-local-cutover-state.sh docs/production-runbook.md
git commit -m "ops: capture local cutover evidence"
```

### Task 2: Make the isolated local rehearsal use immutable release images

**Files:**
- Create: `scripts/deploy/validate-local-release-inputs.sh`
- Create: `scripts/deploy/test-validate-local-release-inputs.sh`
- Modify: `scripts/deploy/prepare-local-production.sh`
- Modify: `scripts/smoke-deploy.sh`
- Modify: `deploy/production/compose.yml`
- Modify: `deploy/production/Caddyfile`
- Modify: `deploy/production/README.md`
- Modify: `scripts/security/test-production-publication.sh`
- Create: `scripts/deploy/test-prepare-local-production.sh`

**Interfaces:**
- Consumes: full release SHA, GHCR image coordinates/digests, source PostgreSQL secret files, Academy/Zeladoria secret files, and the existing offline signing keys.
- Produces: an isolated `cortex-production-rehearsal` stack that coexists with the Apache canary and never builds mutable images on the server.

- [x] **Step 1: Write RED contracts for immutable inputs and coexistence**

Require a 40-hex `CORTEX_RELEASE_SHA`, `ghcr.io/...@sha256:<64 hex>` API/PWA images, a unique rehearsal project name, loopback-only port binding, no `docker compose build`, and no remote mutation command.

- [x] **Step 2: Confirm RED**

```bash
bash scripts/deploy/test-prepare-local-production.sh
```

Expected: failure because the current script uses mutable local tags and builds images.

- [x] **Step 3: Implement immutable rehearsal mode**

Add `CORTEX_PRODUCTION_MODE=rehearsal|cutover`, defaulting to `rehearsal`; require digest-pinned images; retain `CORTEX_PUBLIC_ORIGIN=https://cortex.portalstavias.com.br` and `CORTEX_AUTH_WEBAUTHN_RP_ID=cortex.portalstavias.com.br`; allocate a loopback-only rehearsal port that does not alter Apache. Pull images and verify OCI revision labels before starting any service.

- [x] **Step 4: Preserve the database restore boundary**

Keep the existing custom-format dump and empty-target-only restore. Add source/target count manifests for every public table, excluding no application table, and fail if any count differs after restore. Persist the dump, manifest, and pg_restore log mode `600` outside the checkout.

- [x] **Step 5: Run GREEN and security gates**

```bash
bash scripts/deploy/test-prepare-local-production.sh
bash scripts/security/test-local-compose-security.sh
bash scripts/security/scan-cortex-secrets.sh
```

- [x] **Step 6: Commit**

```bash
git add scripts/deploy/prepare-local-production.sh \
  scripts/deploy/validate-local-release-inputs.sh \
  scripts/deploy/test-validate-local-release-inputs.sh \
  scripts/deploy/test-prepare-local-production.sh scripts/smoke-deploy.sh \
  scripts/security/test-production-publication.sh \
  deploy/production/compose.yml deploy/production/Caddyfile \
  deploy/production/README.md
git commit -m "ops: rehearse immutable local production"
```

### Task 3: Copy and verify R2 objects without changing R2

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/migrate/ObjectStorageMigrationApplication.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/migrate/ObjectStorageMigrationRunner.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/storage/migrate/ObjectStorageMigrationResult.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/storage/migrate/ObjectStorageMigrationRunnerTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/storage/migrate/PostgresqlObjectStorageMigrationIT.java`
- Modify: `deploy/production/compose.yml`
- Modify: `docs/production-runbook.md`
- Modify: `scripts/deploy/prepare-local-production.sh`
- Modify: `scripts/security/test-production-publication.sh`

**Interfaces:**
- Consumes: read-only PostgreSQL metadata, R2 source credentials, private bucket/prefix, local target root, and a mode-600 evidence destination.
- Produces: an idempotent copy-only result with totals for selected, copied, already verified, missing, mismatched, failed, bytes, and a manifest SHA-256.

- [x] **Step 1: Write RED unit tests**

Cover an empty catalog; one available S3 object; an already matching local object; source 404; source size mismatch; SHA-256 mismatch; unsafe storage key; target write failure; rerun idempotency; and proof that the source delete method is never invoked.

- [x] **Step 2: Confirm RED**

```bash
(cd apps/api && ./mvnw -q -Dtest=ObjectStorageMigrationRunnerTest test)
```

Expected: compilation failure because the migration classes do not exist.

- [x] **Step 3: Implement copy-only migration**

Read `stored_object` rows in bounded keyset pages where `status='DISPONIVEL'`. For every row, stream from S3/R2, enforce the database byte count, calculate SHA-256 while copying, write through `LocalObjectStorage`, reopen the local object, and verify bytes, media type, and SHA-256. Never update source metadata and never call source delete.

- [x] **Step 4: Write and run PostgreSQL integration coverage**

The IT must prove paging, mixed `LOCAL`/`S3` metadata, rollback on a failed object, no database mutation, idempotent rerun, and that the manifest contains no names, owners, CPFs, storage keys, endpoints, or credentials.

```bash
(cd apps/api && ./mvnw -q -Dtest=ObjectStorageMigrationRunnerTest,PostgresqlObjectStorageMigrationIT test)
```

- [x] **Step 5: Add the one-shot Compose service**

Create `cortex-object-migrate` with `restart: "no"`, read-only root filesystem, dropped capabilities, source credentials through mounted files, local object volume mounted read-write, and no public port. It must not be a dependency of the live API.

- [x] **Step 6: Run GREEN and commit**

```bash
(cd apps/api && ./mvnw -q -Dtest=ObjectStorageMigrationRunnerTest,PostgresqlObjectStorageMigrationIT test)
bash scripts/security/test-local-compose-security.sh
git add apps/api/src/main/java/com/projeto/cortex/storage/migrate \
  apps/api/src/test/java/com/projeto/cortex/storage/migrate \
  deploy/production/compose.yml docs/production-runbook.md \
  scripts/deploy/prepare-local-production.sh \
  scripts/security/test-production-publication.sh
git commit -m "ops: migrate R2 objects to local storage"
```

### Task 4: Add atomic Apache cutover and rollback commands

**Files:**
- Create: `scripts/deploy/cutover-local-production.sh`
- Create: `scripts/deploy/rollback-local-production.sh`
- Create: `scripts/deploy/test-local-production-cutover.sh`
- Modify: `docs/production-runbook.md`

**Interfaces:**
- Consumes: exact rehearsal evidence, final dump/object manifest, Apache site path, previous upstream, candidate upstream, and expected full SHA.
- Produces: one timestamped root-owned backup of Apache configuration, an atomic symlink/config switch, `apachectl configtest`, bounded health verification, and automatic rollback on failure.

- [ ] **Step 1: Write RED shell tests**

Cover invalid SHA, missing final evidence, mismatched database/object manifest, failed `configtest`, candidate timeout, rollback restoration, symlink target rejection, and successful exact-revision switch.

- [ ] **Step 2: Confirm RED**

```bash
bash scripts/deploy/test-local-production-cutover.sh
```

- [ ] **Step 3: Implement fail-closed cutover**

Require an explicit `CORTEX_CUTOVER_APPROVED=true`, verify no remote-deletion executable is present, freeze writes through the local maintenance response, take the final database dump and object delta, start candidate services, validate direct API/PWA, atomically switch Apache, reload it, and validate the public origin. Any failure restores Apache first and leaves both remote platforms untouched.

- [ ] **Step 4: Implement rollback**

Rollback must be safe to rerun, restore the exact prior Apache configuration, reload Apache only after `configtest`, keep the candidate database and object volumes stopped but intact, and emit redacted mode-600 evidence.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash scripts/deploy/test-local-production-cutover.sh
bash scripts/security/scan-cortex-secrets.sh
git add scripts/deploy/cutover-local-production.sh \
  scripts/deploy/rollback-local-production.sh \
  scripts/deploy/test-local-production-cutover.sh docs/production-runbook.md
git commit -m "ops: add atomic local production cutover"
```

### Task 5: Prove backup, restore, and off-host recovery

**Files:**
- Create: `scripts/deploy/backup-local-production.sh`
- Create: `scripts/deploy/verify-local-production-backup.sh`
- Create: `scripts/deploy/test-local-production-backup.sh`
- Modify: `deploy/production/README.md`
- Modify: `docs/production-runbook.md`

**Interfaces:**
- Consumes: local PostgreSQL and object volumes plus a root-owned off-host destination.
- Produces: encrypted database dump, object archive, redacted manifest, retention metadata, and a restore-drill result.

- [ ] **Step 1: Write RED backup contracts**

Reject a backup destination on the same Docker volume/device, a world-readable destination, missing encryption recipient, partial archives, count/hash mismatches, and stale backups. Prove temporary plaintext is removed on success and failure.

- [ ] **Step 2: Confirm RED**

```bash
bash scripts/deploy/test-local-production-backup.sh
```

- [ ] **Step 3: Implement backup and validation**

Use PostgreSQL 18 custom-format dump, a deterministic object manifest, streaming authenticated encryption to the off-host destination, atomic final rename, and retention that never removes the last known-good backup. Do not back up live secret values into CI or Git; use the host secret escrow procedure.

- [ ] **Step 4: Execute a disposable restore drill**

Restore into a separate PostgreSQL volume and object directory, run table count/hash checks and API readiness against the disposable stack, then destroy only the disposable resources.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash scripts/deploy/test-local-production-backup.sh
bash scripts/security/scan-cortex-secrets.sh
git add scripts/deploy/backup-local-production.sh \
  scripts/deploy/verify-local-production-backup.sh \
  scripts/deploy/test-local-production-backup.sh \
  deploy/production/README.md docs/production-runbook.md
git commit -m "ops: back up and restore local production"
```

### Task 6: Deploy the current immutable SHA to the existing canary

**Files:**
- Evidence only: server mode-600 deployment record outside the checkout.

**Interfaces:**
- Consumes: attested API/PWA images for `63f315df6bd0377b162851d22bdd4f644a938c2b` and current canary backups.
- Produces: local canary serving the same exact SHA while retaining its current Neon/R2 connections and a tested container-level rollback.

- [ ] **Step 1: Regain SSH access from the Stavias LAN/VPN**

```bash
ssh -o ConnectTimeout=5 sistema@192.168.0.15 'hostname; date -Is'
```

- [ ] **Step 2: Capture current server evidence and backup canary files**

Do not display secret values. Copy Compose/env/Apache configuration to timestamped mode-600 backups and record current image IDs, revisions, health, restart counts, disk space, and volume list.

- [ ] **Step 3: Pull and attest the exact images**

Verify GHCR provenance against this repository, pull digest-pinned images, and compare each OCI revision label to the full SHA before changing Compose.

- [ ] **Step 4: Update canary images only**

Keep database and storage settings unchanged. Run `docker compose config`, recreate API/PWA without builds, and automatically restore the previous image references if readiness fails.

- [ ] **Step 5: Verify exact public runtime**

Require 20 consecutive successful checks of `/healthz`, `/api/health`, `/api/readiness`, and `/api/wake`, with the expected full revision and no Apache 502, container restart, or OOM.

### Task 7: Rehearse and execute the local data cutover

**Files:**
- Evidence only: mode-600 rehearsal and cutover records outside the checkout.

**Interfaces:**
- Consumes: completed Tasks 1-6, current source credentials, free disk space at least three times database plus object size, and an off-host verified backup.
- Produces: local PostgreSQL/object candidate, then the Apache-served local canonical runtime, with remote rollback systems unchanged.

- [ ] **Step 1: Run an isolated rehearsal with live traffic unchanged**

Create a fresh rehearsal project/volumes, restore a new Neon dump, copy R2 objects, compare table counts and object hashes, run Flyway, and validate direct candidate readiness.

- [ ] **Step 2: Validate authenticated and offline behavior in rehearsal**

Test CPF/password, first-access code, passkey, RDO list/detail hydration in one load, create/edit/export PDF/XLSX, messages and attachments, Academy/Zeladoria isolated scheduling, fully offline create/edit/photo/attachment, reconnect, exactly-once replay, and PDOR/revenue consequences.

- [ ] **Step 3: Run the final bounded write freeze**

Prevent new server writes while leaving PWA offline work intact. Take the final Neon dump and R2 delta, restore/copy into fresh cutover volumes, compare counts/hashes, and run migrations.

- [ ] **Step 4: Switch Apache atomically**

Execute `cutover-local-production.sh`, verify the public exact SHA and functional journey, then reopen writes. Do not change the Locaweb DNS record and do not touch the remote services.

- [ ] **Step 5: Observe for seven days**

Monitor readiness, disk, backups, PostgreSQL, objects, Apache 5xx, auth failures, outbox age, RDO completeness, Academy/Zeladoria runs, attachment integrity, and container restarts. Run at least one off-host restore drill during this period.

### Task 8: Change release automation to server-only after the observation gate

**Files:**
- Modify: `.github/workflows/production.yml`
- Modify: `.github/workflows/api-keepwarm.yml`
- Modify: `.github/workflows/keepwarm-deploy.yml`
- Modify: `scripts/security/test-production-workflow-contract.sh`
- Modify: `scripts/security/test-production-workflow-contract-regressions.sh`
- Modify: `scripts/security/test-production-publication.sh`
- Modify: `docs/production-runbook.md`

**Interfaces:**
- Consumes: seven-day local evidence, verified off-host restore, and explicit user approval for release-path change.
- Produces: CI that tests, builds, attests, and publishes immutable GHCR images without migrating Neon or deploying Render/Cloudflare; remote services remain intact but idle.

- [ ] **Step 1: Write RED workflow contracts**

Require CI/tests/images/attestations, forbid Neon migration/Render deploy/Pages deploy/keepwarm in the active workflow, and require a local-server release handoff that names the exact image digests without embedding SSH or host secrets in GitHub.

- [ ] **Step 2: Confirm RED**

```bash
bash scripts/security/test-production-workflow-contract.sh
bash scripts/security/test-production-workflow-contract-regressions.sh
bash scripts/security/test-production-publication.sh
```

- [ ] **Step 3: Implement the server-only publication boundary**

Keep GHCR build/provenance and all test gates. Remove only automatic remote migration/deploy steps from the active release job; do not delete remote resources or credentials in this task. Generate a signed release handoff consumed by the server deployment process.

- [ ] **Step 4: Run the full release gate**

```bash
bash scripts/security/test-production-workflow-contract.sh
bash scripts/security/test-production-workflow-contract-regressions.sh
bash scripts/security/test-production-publication.sh
bash scripts/security/scan-cortex-secrets.sh
(cd apps/web && npm test -- --run && npm run lint && npm run build)
(cd apps/api && ./mvnw -q test)
```

- [ ] **Step 5: Commit and push only after the seven-day gate**

```bash
git add .github/workflows/production.yml \
  .github/workflows/api-keepwarm.yml .github/workflows/keepwarm-deploy.yml \
  scripts/security/test-production-workflow-contract.sh \
  scripts/security/test-production-workflow-contract-regressions.sh \
  scripts/security/test-production-publication.sh docs/production-runbook.md
git commit -m "ops: publish Córtex for the Stavias server"
git push origin HEAD:develop
```

## Completion Boundary

This plan finishes when the Stavias server has served the exact current SHA for seven days from local PostgreSQL 18 and local persistent object storage, daily encrypted off-host backups and a restore drill are proven, Academy/Zeladoria and the complete offline journey are accepted, and CI publishes server-consumable immutable images without touching remote runtime services. Deleting Neon, Render, Cloudflare Pages, or R2 is explicitly outside this plan and requires a later approval.

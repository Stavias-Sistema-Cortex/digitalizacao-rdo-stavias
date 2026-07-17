# Córtex Runtime, Offline and Ontology Validation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce fresh, repeatable evidence that every protected Córtex tab survives a real offline cycle, queues authorized work, synchronizes automatically after reconnection and preserves a complete ontology trail.

**Architecture:** Add a disposable runtime harness beside the existing `smoke-stavia-sync.sh`: MySQL 8.4 runs in an ephemeral Docker container, the API runs under JDK 21 on a free port, and a locally built PWA runs through Vite preview. A Node script drives Microsoft Edge through CDP, uses a virtual WebAuthn authenticator where PRF is supported, toggles network conditions, exercises the route matrix and queries the real API/database for reconciliation and ontology evidence.

**Tech Stack:** Bash, Node.js built-in test runner, Microsoft Edge CDP, WebAuthn, Vite preview, Workbox, Java 21, Spring Boot, MySQL 8.4, Docker, `curl`, `jq`.

## Global Constraints

- Requires completion of the foundation, tab migration and institutional UI plans.
- Use a disposable database and browser profile; never mutate the user's normal local database or Edge profile.
- Validate a production PWA build because the development server does not register the service worker.
- Do not invoke the manual sync button or call `syncNow()` from the browser script.
- Do not seed fabricated business results. Fixtures may create named validation actors, worksites and records, but assertions must inspect values actually persisted by the API.
- A queued external integration may end in `REJEITADA` or `ERRO` when no provider is configured; it must never become a synthetic success.
- A missing PRF-capable CDP virtual authenticator is a hard environment limitation for automated offline unlock, not a pass. Record the limitation and obtain a real-browser pass before declaring full validation.
- Use JDK 21 for every Maven command.
- Keep screenshots, logs and disposable credentials under an ignored temporary directory; commit only the textual evidence report.

---

### Task 1: Executable route and mutation scenario manifest

**Files:**
- Create: `scripts/validation/cortex-runtime-scenarios.mjs`
- Create: `scripts/validation/cortex-runtime-scenarios.test.mjs`
- Test: `apps/web/src/offlineTabCoverage.test.ts`

**Interfaces:**
- Produces: `CORTEX_RUNTIME_SCENARIOS`, one row per protected route.
- Each row declares `route`, `readEvidence`, `offlineActions`, `expectedOperation`, `requiredCapability` and `memoryEntityType`.

- [ ] **Step 1: Write the failing manifest coverage test**

```js
const expectedRoutes = [
  "/home",
  "/rdos",
  "/obras",
  "/obras/gestao",
  "/equipes",
  "/mensagens",
  "/tarefas",
  "/financeiro",
  "/integracoes",
  "/seguranca",
];

assert.deepEqual(
  CORTEX_RUNTIME_SCENARIOS.map(({ route }) => route),
  expectedRoutes,
);
```

Require at least one local read assertion for every route. Routes with a write surface must declare a canonical operation; genuinely read-only surfaces must declare the exact reason.

- [ ] **Step 2: Run and verify RED**

Run: `node --test scripts/validation/cortex-runtime-scenarios.test.mjs`

Expected: FAIL because the manifest does not exist.

- [ ] **Step 3: Define the exact scenario matrix**

Cover RDO draft/save/attachment, obra metadata/geometry/link, team/member, message/attachment, task lifecycle, revenue recalculation, integration command and access-role change. Home verifies local projections and Memory conflict review; Segurança verifies the signed grant/device register.

- [ ] **Step 4: Cross-check the web coverage contract**

Run: `cd apps/web && npm test -- --run src/offlineTabCoverage.test.ts`

Expected: PASS with the same route and operation set.

- [ ] **Step 5: Commit**

```bash
git add scripts/validation/cortex-runtime-scenarios.mjs scripts/validation/cortex-runtime-scenarios.test.mjs apps/web/src/offlineTabCoverage.test.ts
git commit -m "test: define complete Cortex runtime matrix"
```

### Task 2: Disposable API, database and PWA harness

**Files:**
- Create: `scripts/validation/run-cortex-runtime.sh`
- Create: `scripts/validation/runtime-harness.test.mjs`
- Reference: `scripts/dev/smoke-stavia-sync.sh`
- Reference: `scripts/dev/run-api.sh`

**Interfaces:**
- Produces: random API, preview, MySQL and Edge debugging ports.
- Exports to the CDP runner: `CORTEX_RUNTIME_API_URL`, `CORTEX_RUNTIME_WEB_URL`, `CORTEX_RUNTIME_CDP_URL`, `CORTEX_RUNTIME_FIXTURE_FILE` and `CORTEX_RUNTIME_ARTIFACT_DIR`.

- [ ] **Step 1: Write shell-contract tests**

Assert that the harness has `set -Eeuo pipefail`, traps cleanup, uses `mktemp`, chooses free ports, creates a unique MySQL container, selects JDK 21, runs `npm run build:local`, starts `npm run preview:local -- --port <free-port>`, and never reads the user's committed/local `.env` credentials.

- [ ] **Step 2: Run and verify RED**

Run: `node --test scripts/validation/runtime-harness.test.mjs`

Expected: FAIL because the harness is missing.

- [ ] **Step 3: Implement deterministic setup and cleanup**

Reuse the readiness, session-material and disposable MySQL patterns from `scripts/dev/smoke-stavia-sync.sh`. Generate fixture IDs and passwords inside the temporary directory. Start the API with `CORTEX_SYNC_ENABLED=true`, deterministic StavIA modes, exact preview origin in CORS, and a JDBC URL ending in `serverTimezone=UTC`.

- [ ] **Step 4: Seed only authoritative runtime fixtures**

Seed one ALFA validator, one BETA validator, two devices, one obra and the minimum existing server aggregates needed to exercise the UI. Create authenticated sessions through the same server-side session tables/helpers already used by the smoke script; do not bypass controller authorization.

- [ ] **Step 5: Prove harness readiness without browser assertions**

Run: `scripts/validation/run-cortex-runtime.sh --preflight-only`

Expected: API `/api/health` returns 2xx, preview serves `index.html`, `manifest.webmanifest` and `sw.js`, and cleanup removes the container and processes.

- [ ] **Step 6: Commit**

```bash
git add scripts/validation/run-cortex-runtime.sh scripts/validation/runtime-harness.test.mjs
git commit -m "test: add disposable Cortex runtime harness"
```

### Task 3: Edge CDP PWA, authentication and cached-read proof

**Files:**
- Create: `scripts/validation/cortex-runtime-cdp.mjs`
- Create: `scripts/validation/cortex-runtime-cdp.test.mjs`
- Modify: `scripts/validation/run-cortex-runtime.sh`

**Interfaces:**
- Consumes: the harness environment and runtime manifest.
- Produces: newline-delimited JSON evidence and route screenshots in the temporary artifact directory.

- [ ] **Step 1: Write CDP protocol tests around a fake WebSocket transport**

Cover request IDs, event waiting, timeouts, target attachment, `Network.emulateNetworkConditions`, IndexedDB evaluation, screenshot capture and sensitive-value redaction.

- [ ] **Step 2: Run and verify RED**

Run: `node --test scripts/validation/cortex-runtime-cdp.test.mjs`

Expected: FAIL because the CDP client is missing.

- [ ] **Step 3: Implement a clean Edge launch**

Launch `/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge` with `--headless=new`, `--remote-debugging-port=<free-port>`, `--user-data-dir=<temporary-profile>`, and no extension/profile reuse. Enable `Page`, `Runtime`, `Network`, `ServiceWorker` and `WebAuthn` domains.

- [ ] **Step 4: Provision the real online-to-offline authentication seam**

Use the online session to register a virtual authenticator and request the real signed offline grant. Require a PRF-capable authenticator; lock/unlock must execute through `OfflineUnlockPage` and `unlockOfflineVault`, not by assigning an in-memory offline profile.

- [ ] **Step 5: Prove cached reads after a cold offline reload**

Visit every scenario online once, await `navigator.serviceWorker.ready`, close the page, emulate offline, open a fresh target, unlock offline and revisit every route. Assert shell content, route-specific local evidence, no unexpected fetch success and no horizontal overflow at 1440×1000 and 390×844.

- [ ] **Step 6: Run the focused runtime proof**

Run: `scripts/validation/run-cortex-runtime.sh --scenario cached-reads`

Expected: all protected routes load from the production service worker/local stores after the cold offline reopen.

- [ ] **Step 7: Commit**

```bash
git add scripts/validation/cortex-runtime-cdp.mjs scripts/validation/cortex-runtime-cdp.test.mjs scripts/validation/run-cortex-runtime.sh
git commit -m "test: verify Cortex PWA cached reads through Edge"
```

### Task 4: Offline mutation queue and reload persistence proof

**Files:**
- Modify: `scripts/validation/cortex-runtime-cdp.mjs`
- Modify: `scripts/validation/cortex-runtime-scenarios.mjs`
- Modify: `scripts/validation/cortex-runtime-cdp.test.mjs`

**Interfaces:**
- Produces: one `clientMutationId`, `ontologyEventId`, payload hash and queued-state assertion for every offline action.

- [ ] **Step 1: Add failing queue-evidence unit tests**

Require each browser action to assert all three local records: the domain projection, `outbox_mutations` status `PENDING`, and a correlated `operational_events` row. Require actor, device, scope, operation, entity, correlation ID and hash.

- [ ] **Step 2: Run and verify RED**

Run: `node --test scripts/validation/cortex-runtime-cdp.test.mjs scripts/validation/cortex-runtime-scenarios.test.mjs`

Expected: FAIL until action/evidence adapters are implemented.

- [ ] **Step 3: Exercise every authorized offline write**

Execute the operation matrix while `navigator.onLine === false`. For an external integration command, assert `PENDING` with `blockedReason === "AGUARDANDO_REDE"`. For a BETA administrative attempt, assert a direct authorization error and no domain/outbox/event write.

- [ ] **Step 4: Reload between queueing and inspection**

Close and reopen the target before reading IndexedDB. Verify attachments/Blobs, geometry, drafts and optimistic projections remain present and no duplicate mutation/event was created.

- [ ] **Step 5: Run the offline-write scenario**

Run: `scripts/validation/run-cortex-runtime.sh --scenario offline-writes`

Expected: every permitted action remains queued after reload; unauthorized work leaves no mutation; no network request reports false success.

- [ ] **Step 6: Commit**

```bash
git add scripts/validation/cortex-runtime-cdp.mjs scripts/validation/cortex-runtime-cdp.test.mjs scripts/validation/cortex-runtime-scenarios.mjs
git commit -m "test: prove every Cortex mutation survives offline reload"
```

### Task 5: Automatic reconnection and server reconciliation proof

**Files:**
- Modify: `scripts/validation/cortex-runtime-cdp.mjs`
- Create: `scripts/validation/assert-runtime-database.mjs`
- Create: `scripts/validation/assert-runtime-database.test.mjs`
- Modify: `scripts/validation/run-cortex-runtime.sh`

**Interfaces:**
- Produces: browser queue-drain evidence plus server rows and immutable event IDs.

- [ ] **Step 1: Write the failing automatic-sync assertion**

The CDP script records the outbox count, restores network only through `Network.emulateNetworkConditions`, emits no click on a sync control, waits for the scheduler, then requires each eligible mutation to leave `PENDING`/`SYNCING`.

- [ ] **Step 2: Write database assertion tests**

Parse MySQL JSON output and require one `sync_mutacao_cliente` row, one applied/rejected result and one correlated operational event per `clientMutationId`. Duplicate replay must not create a second domain change or event.

- [ ] **Step 3: Run and verify RED**

Run: `node --test scripts/validation/cortex-runtime-cdp.test.mjs scripts/validation/assert-runtime-database.test.mjs`

Expected: FAIL until automatic drain and database assertions are implemented.

- [ ] **Step 4: Verify all scheduler triggers**

Run separate passes for network reconnection, page startup, foreground return and a newly unlocked valid offline session. Assert exponential retry metadata after a forced transient 503 and automatic retry after the backoff window.

- [ ] **Step 5: Verify real server outcomes**

Query MySQL through `docker exec`, then read the same entities through authenticated API endpoints. Integration commands may be rejected when a provider is absent, but the browser and Memory result must match the server result exactly.

- [ ] **Step 6: Run the reconnection scenario**

Run: `scripts/validation/run-cortex-runtime.sh --scenario automatic-reconnect`

Expected: all eligible work synchronizes without a manual trigger; every outcome is idempotent and trace-correlated.

- [ ] **Step 7: Commit**

```bash
git add scripts/validation/cortex-runtime-cdp.mjs scripts/validation/assert-runtime-database.mjs scripts/validation/assert-runtime-database.test.mjs scripts/validation/run-cortex-runtime.sh
git commit -m "test: prove automatic idempotent Cortex reconciliation"
```

### Task 6: Field conflict and Memory exclusivity proof

**Files:**
- Modify: `scripts/validation/cortex-runtime-cdp.mjs`
- Modify: `scripts/validation/assert-runtime-database.mjs`
- Modify: `scripts/validation/cortex-runtime-cdp.test.mjs`

**Interfaces:**
- Proves: different-field auto-merge, same-field preservation, immutable resolution event and full-history exclusivity.

- [ ] **Step 1: Write the conflict scenario assertions**

Create two offline clients from the same base version. Client A changes `titulo`; the server changes `prazo`; reconnection must produce one merged version. In the second case both change `titulo`; the local mutation must remain `CONFLICT` with `base`, `local` and `remote` values.

- [ ] **Step 2: Run and verify RED**

Run: `node --test scripts/validation/cortex-runtime-cdp.test.mjs scripts/validation/assert-runtime-database.test.mjs`

Expected: FAIL until runtime conflict assertions exist.

- [ ] **Step 3: Verify `Home > Memória > Revisão necessária`**

Assert the same-field conflict shows actor, device, entity, operation, timestamps and the three values. Submit a resolution with justification; verify it creates a new mutation/event and the original conflict row remains immutable.

- [ ] **Step 4: Verify complete history appears only in Memory**

Navigate every non-Memory route. Compact trace links may expose an event ID and status, but no other page may render a full actor/action/state timeline. Memory must find every runtime event by event ID, entity, actor and result.

- [ ] **Step 5: Run the conflict scenario**

Run: `scripts/validation/run-cortex-runtime.sh --scenario conflicts`

Expected: different fields merge automatically; same fields require review; resolution is append-only; full history is exclusive to Memory.

- [ ] **Step 6: Commit**

```bash
git add scripts/validation/cortex-runtime-cdp.mjs scripts/validation/cortex-runtime-cdp.test.mjs scripts/validation/assert-runtime-database.mjs
git commit -m "test: prove conflict review and Memory exclusivity"
```

### Task 7: Ontology parity, UTC and mutation coverage gate

**Files:**
- Create: `scripts/validation/assert-ontology-runtime.mjs`
- Create: `scripts/validation/assert-ontology-runtime.test.mjs`
- Modify: `scripts/validation/run-cortex-runtime.sh`

**Interfaces:**
- Compares: web operation manifest, API `SyncOperationRegistry`, `OperationalMutationCatalog`, applied server rows and Memory events.

- [ ] **Step 1: Write exact-set and temporal tests**

Reject missing or extra operations in any layer. Require every applied runtime mutation to have one catalog entry and Memory event. Parse all HTTP timestamps and require an explicit `Z` or numeric offset; compare instants rather than formatted local time.

- [ ] **Step 2: Run and verify RED**

Run: `node --test scripts/validation/assert-ontology-runtime.test.mjs`

Expected: FAIL because the runtime parity checker does not exist.

- [ ] **Step 3: Implement runtime extraction**

Read the browser manifest directly, expose registry/catalog operation names through a test-only JVM invocation or existing service methods, and query applied fixture mutation/event IDs from the disposable database. Do not regex Java source as the decisive proof.

- [ ] **Step 4: Run under the non-UTC workstation timezone**

Run: `TZ=America/Sao_Paulo scripts/validation/run-cortex-runtime.sh --scenario ontology`

Expected: exact operation equality, full event coverage and identical instants across database, API and Memory.

- [ ] **Step 5: Commit**

```bash
git add scripts/validation/assert-ontology-runtime.mjs scripts/validation/assert-ontology-runtime.test.mjs scripts/validation/run-cortex-runtime.sh
git commit -m "test: enforce runtime ontology and UTC parity"
```

### Task 8: Final full-story gate and evidence report

**Files:**
- Create: `docs/validation/2026-07-17-cortex-institutional-offline-ontology.md`
- Modify only implementation/tests exposed by fresh failures.

**Interfaces:**
- Produces: one evidence-backed launch-readiness report with commands, counts, route matrix, screenshots, runtime IDs, limitations and residual risks.

- [ ] **Step 1: Run static and automated web gates**

Run: `cd apps/web && npm run lint && npm test -- --run && npm run build`

Expected: exit 0 and generated PWA assets.

- [ ] **Step 2: Run the full API suite with JDK 21**

Run: `cd apps/api && TZ=America/Sao_Paulo JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test`

Expected: exit 0.

- [ ] **Step 3: Run existing and new integration smokes**

Run: `scripts/dev/smoke-stavia-sync.sh`

Run: `scripts/validation/run-cortex-runtime.sh --scenario all`

Expected: both exit 0; the new harness proves cached reads, offline writes, automatic reconnect, conflicts and ontology parity.

- [ ] **Step 4: Inspect repository hygiene**

Run: `git diff --check && git status --short && git log --oneline -30`

Expected: no whitespace errors, no temporary credentials/artifacts and focused commits matching the implementation plans.

- [ ] **Step 5: Write the report from fresh artifacts**

Include exact pass counts and runtime event/mutation IDs, but redact tokens, CPF material and passwords. Mark any skipped scenario as unverified; do not use a prior report as proof.

- [ ] **Step 6: Commit**

```bash
git add docs/validation/2026-07-17-cortex-institutional-offline-ontology.md
git commit -m "docs: record complete Cortex runtime validation"
```

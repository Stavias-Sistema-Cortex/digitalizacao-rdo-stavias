# Cortex 3.0 Security and Completion Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate and remediate frontend/backend/key security, then prove every Cortex 3.0 requirement against a fresh PostgreSQL runtime and real offline/reconnect/export flows.

**Architecture:** Security contracts land before the final scan; the scan freezes the diff, uses the `codex-security:deep-security-scan` workflow, validates candidates centrally, and repeats until novelty is zero. A completion matrix maps each approved requirement to current code, command output, runtime/browser evidence, or generated artifact.

**Tech Stack:** Spring Security filters/session/CSRF, Java/JUnit/MockMvc, PostgreSQL/Testcontainers, React/Vite, browser automation, Codex Security skills, shell/static secret scanning.

## Global Constraints

- Do not claim production proof from static tests or local fixtures.
- Do not edit the repository during a deep-scan discovery round; remediate between rounds.
- Every candidate finding must be validated before inclusion.
- Worksite/entity object authorization is distinct from authentication.
- Secrets are file/environment/secret-manager injected and startup is fail-closed.
- Local caches are partitioned by user and cannot leak across logout/login.
- Export/search/upload/graph/PDOR endpoints have explicit bounded resource policies.
- Completion requires evidence for every original requirement, not absence of known failures.

---

### Task 1: Add object-authorization and entity-reference security contracts

**Files:**
- Create: `apps/api/src/test/java/com/projeto/cortex/security/Cortex3ObjectAuthorizationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/security/OperationalEntityReferenceValidationTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoContextController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMemoryController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OntologyGraphController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/catalog/ServicePriceCatalogController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`

**Interfaces:**
- Consumes: authenticated sessions for worksite A/B/admin and IDs across RDO/export/memory/graph/price/PDOR.
- Produces: uniform forbidden/not-found behavior without foreign object leakage.

- [ ] **Step 1: Write the cross-worksite matrix**

```java
@ParameterizedTest
@MethodSource("foreignObjectRequests")
void deniesForeignObjects(RequestBuilder request) throws Exception {
    mockMvc.perform(withSession(request, sessionFor(WORKSITE_A)))
            .andExpect(status().isForbidden())
            .andExpect(content().string(not(containsString(WORKSITE_B))))
            .andExpect(content().string(not(containsString(FOREIGN_OBJECT_NAME))));
}
```

Include RDO detail/update/export, creation context, service price/history, revenue trace, PDOR current/history/calculate, Memory filters, graph detail/traversal, and attachment content.

- [ ] **Step 2: Write related-entity validation tests**

Reject unknown type, malformed ID, non-existent ID, type/ID mismatch, foreign worksite, excessive list length, duplicate/conflicting references, and RDO/worksite inconsistency before handler execution.

- [ ] **Step 3: Run and verify RED on real gaps**

Run: `mvn -f apps/api/pom.xml -Dtest=Cortex3ObjectAuthorizationTest,OperationalEntityReferenceValidationTest test`

Expected: any failing case identifies a concrete missing check; do not weaken assertions to match current behavior.

- [ ] **Step 4: Centralize validation and rerun**

Implement one `OperationalEntityAuthorizationService.requireReferences(CurrentUser, obraId, principal, related)` with bounded count and repository-backed ownership checks. Controllers authorize before loading response bodies.

Run: `mvn -f apps/api/pom.xml -Dtest=Cortex3ObjectAuthorizationTest,OperationalEntityReferenceValidationTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main apps/api/src/test/java/com/projeto/cortex/security
git commit -m "fix(security): enforce Cortex object authorization"
```

### Task 2: Bound resource abuse and harden web delivery

**Files:**
- Create: `apps/api/src/test/java/com/projeto/cortex/security/Cortex3ResourceLimitTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/security/Cortex3ErrorRedactionTest.java`
- Modify: storage/search/graph/export/PDOR request validation and configuration.
- Create: `apps/web/src/securityDeliveryPolicy.test.ts`
- Modify: Vite/server security header configuration used by the deployed application.

**Interfaces:**
- Consumes: oversized strings/lists/files/depth/pages and malicious workbook values.
- Produces: bounded rejection with stable safe codes, correlation IDs, and no sensitive details.

- [ ] **Step 1: Write resource-limit tests**

Test exact limits and one-over-limit for Memory query length/page size, graph depth/result size, related-entity count, RDO workforce/service/material rows, XLSX variable rows, multipart envelope and decompressed content, attachment bytes, PDOR request concurrency/time, and export concurrency.

```java
mockMvc.perform(get("/api/ontology/memory").param("q", "x".repeat(513)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SEARCH_QUERY_TOO_LONG"));
```

- [ ] **Step 2: Write delivery/redaction tests**

```ts
expect(builtIndexHtml).not.toMatch(/sourceMappingURL|VITE_.*SECRET|CORTEX_.*KEY/);
expect(securityHeaders).toMatchObject({
  "content-security-policy": expect.stringContaining("script-src 'self'"),
  "x-content-type-options": "nosniff",
});
```

Backend error tests insert SQL/file/CPF/email/secret markers into thrown causes and assert response/log redaction.

- [ ] **Step 3: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=Cortex3ResourceLimitTest,Cortex3ErrorRedactionTest test`

Run: `npm --prefix apps/web test -- --run src/securityDeliveryPolicy.test.ts`

Expected: expose any missing limits/headers/redaction.

- [ ] **Step 4: Implement centralized limits and safe errors**

Use validated configuration properties with secure defaults and upper bounds. Apply endpoint-specific rate-limit buckets to authentication, export, search, upload, graph traversal, and PDOR. Keep CORS allowlist/CSRF/session-cookie policy fail-closed outside explicit local profile.

- [ ] **Step 5: Verify and commit**

Run the tests above plus `npm --prefix apps/web run build` and inspect `apps/web/dist` for source maps/secrets.

```bash
git add apps/api apps/web
git commit -m "fix(security): bound Cortex resource and delivery risks"
```

### Task 3: Prove user-scoped offline cache isolation

**Files:**
- Create: `apps/web/src/lib/db/cortexUserIsolation.test.ts`
- Modify: `apps/web/src/lib/db/localDataNamespace.ts`
- Modify: `apps/web/src/lib/db/localDataScope.ts`
- Modify: `apps/web/src/lib/db/cortexDb.ts`
- Modify: `apps/web/src/features/auth/authSession.ts`

**Interfaces:**
- Consumes: user A login/write/logout then user B login on the same browser profile.
- Produces: inaccessible A records and an atomic namespace switch for B.

- [ ] **Step 1: Write failing two-user tests**

```ts
await loginAs(USER_A);
await seedSensitiveLocalRdo({ userId: USER_A, obraId: OBRA_A });
await logout();
await loginAs(USER_B);
expect(await listRdos()).toEqual([]);
expect(await searchMemory("obra-a")).toEqual([]);
expect(await readOutboxForActiveUser()).toEqual([]);
```

Simulate a crash during identity switch and assert reopen selects either the complete old namespace before logout or the complete new empty namespace, never a mixed scope.

- [ ] **Step 2: Run and verify RED**

Run: `npm --prefix apps/web test -- --run src/lib/db/cortexUserIsolation.test.ts`

Expected: FAIL if any store is unscoped or switch is non-atomic.

- [ ] **Step 3: Implement per-user namespace enforcement**

Every key/index includes the stable authenticated subject. Reject repository calls without active scope. On logout, close handles, clear session-derived keys, and switch namespace transactionally; preserve encrypted offline data only under its original subject.

- [ ] **Step 4: Verify and commit**

Run: `npm --prefix apps/web test -- --run src/lib/db src/features/auth`

Expected: PASS.

```bash
git add apps/web/src/lib/db apps/web/src/features/auth
git commit -m "fix(security): isolate offline data by user"
```

### Task 4: Audit secrets and fail-closed key configuration

**Files:**
- Create: `apps/api/src/test/java/com/projeto/cortex/security/Cortex3SecretConfigurationTest.java`
- Create: `scripts/security/scan-cortex-secrets.sh`
- Create: `docs/verification/cortex-3/secret-audit.md`
- Modify: application/compose/CI configuration only for validated findings.

**Interfaces:**
- Consumes: current tree, Git history, frontend bundle, runtime logs, generated XLSX, environment configuration names.
- Produces: redacted audit and startup rejection for unsafe key state.

- [ ] **Step 1: Write failing startup policy tests**

```java
@ParameterizedTest
@ValueSource(strings = {"", "change-me", "default", "test-secret", "same-as-other-key"})
void rejectsUnsafeProductionKeyMaterial(String value) {
    assertThatThrownBy(() -> productionConfiguration(value))
            .hasMessageContaining("UNSAFE_SECRET_CONFIGURATION")
            .hasMessageNotContaining(value);
}
```

Cover CPF HMAC key ring/current key ID, offline grant signing, session hashing, CSRF, SMTP credentials, Academy/Zeladoria credentials, and object storage keys.

- [ ] **Step 2: Implement a non-printing scanner**

The script reports file path, line, detector name, and fingerprint only; it never prints secret values. Scan tracked files/history, `apps/web/dist`, generated XLSX ZIP members, logs, and compose/CI files for known key names and high-entropy candidates. Maintain a fingerprint allowlist for proven test fixtures/public identifiers.

- [ ] **Step 3: Run audit and validate candidates**

Run: `mvn -f apps/api/pom.xml -Dtest=Cortex3SecretConfigurationTest test`

Run: `bash scripts/security/scan-cortex-secrets.sh`

Expected: tests PASS after configuration fixes; scanner exits 0 only when no unreviewed candidate remains.

- [ ] **Step 4: Record safe evidence and commit**

Document sources/status/key IDs without values, commands, detector counts, reviewed false positives, and environmental prerequisites.

```bash
git add apps/api scripts/security/scan-cortex-secrets.sh docs/verification/cortex-3/secret-audit.md
git commit -m "test(security): audit Cortex key handling"
```

### Task 5: Run the deep security workflow and remediate validated findings

**Files:**
- Create: `docs/security/cortex-3-deep-scan.md`
- Modify: only files required by centrally validated findings, between scan rounds.

**Interfaces:**
- Consumes: frozen integrated Cortex 3 diff and running test environment.
- Produces: validated findings, remediations, retests, and a zero-novelty discovery round.

- [ ] **Step 1: Load required Codex Security skills and preflight**

Read completely: `security-scan`, `threat-model`, `finding-discovery`, `validation`, `attack-path-analysis`, configuration preflight, security guidance, final report, and `deep-security-scan`. Confirm repository root, diff/base, build commands, data flows, trust boundaries, and no concurrent repo editor.

- [ ] **Step 2: Freeze the diff and launch one complete discovery round**

Follow `codex-security:deep-security-scan` exactly. Use six independent workers in the round, with distinct focus: authentication/session/CSRF, object authorization/ontology traversal, offline/browser/XSS, uploads/exports/resource abuse, PostgreSQL/SQL/migrations, and secrets/dependencies/configuration. Discovery workers do not edit files.

- [ ] **Step 3: Validate centrally and rank attack paths**

For each candidate, reproduce data/control flow, confirm reachability and impact, reject duplicates/false positives, and use attack-path analysis for connected findings. Store only validated evidence in the report.

- [ ] **Step 4: Remediate between rounds with TDD**

For every validated in-scope finding, use `codex-security:fix-finding`: add a failing regression test, implement the narrow fix, rerun targeted and affected suites, and commit by coherent finding group.

- [ ] **Step 5: Repeat discovery until novelty is zero**

Run a new complete independent round after remediation. Stop only when a full round adds zero novel validated findings. Record round composition, candidates, validation outcomes, and commands without exposing secrets.

- [ ] **Step 6: Commit final security report**

```bash
git add apps/api apps/web docs/security/cortex-3-deep-scan.md
git commit -m "security: validate Cortex 3 attack surface"
```

### Task 6: Prove clean PostgreSQL runtime and the complete offline flow

**Files:**
- Create: `docs/verification/cortex-3/runtime-evidence.md`
- Create: `docs/verification/cortex-3/completion-matrix.md`
- Create/update browser screenshots and workbook artifacts under `docs/verification/cortex-3/`.
- Create: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCortex3FlowIT.java`

**Interfaces:**
- Consumes: all prior plans and approved design.
- Produces: requirement-by-requirement authoritative completion evidence.

- [ ] **Step 1: Write the failing PostgreSQL flow IT**

The Testcontainers scenario authenticates a scoped user, creates worksite/catalog/previous RDO fixtures, builds a new RDO with workforce changes, persists one priced execution, replays the mutation, and asserts exactly one RDO/event/graph edge/revenue plus cost-free PDOR evidence.

- [ ] **Step 2: Run all automated verification**

Run:

```bash
mvn -f apps/api/pom.xml test
mvn -f apps/api/pom.xml -Ppostgresql-it verify
npm --prefix apps/web test -- --run
npm --prefix apps/web run lint
npm --prefix apps/web run build
bash scripts/security/scan-cortex-secrets.sh
```

Expected: every command exits 0. Save exact timestamps, versions, commit, counts, and failures/retries if any.

- [ ] **Step 3: Execute browser offline/reload/reconnect scenario**

Use persisted validation fixtures. Authenticate, synchronize, disable network, reload, create RDO from cached obra, change workforce/apontador, record priced service, export offline, reconnect without sync button, and verify authoritative number, Memória search, graph evidence, revenue, PDOR, and no duplicate after second reload.

- [ ] **Step 4: Compare online/offline XLSX and inspect UI viewports**

Open both workbooks structurally, render both sheets, compare fields/rows/merges/print areas, and inspect required viewports and keyboard flow. Store artifacts and exact commands.

- [ ] **Step 5: Fill completion matrix from current evidence**

For each original requirement, record status `PROVEN`, exact source/test/artifact, command, and why it is sufficient. Any `MISSING`, `CONTRADICTED`, or `INDIRECT` row returns to implementation; do not declare completion.

- [ ] **Step 6: Run final clean-tree audit and commit**

Run `git status --short`, inspect every diff, rerun the smallest commands invalidated by final documentation/artifact changes, and commit:

```bash
git add apps/api apps/web docs/verification/cortex-3
git commit -m "test(cortex): prove Cortex 3 completion"
```

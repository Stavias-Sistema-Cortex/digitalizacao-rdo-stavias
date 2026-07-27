# CPF direto e acesso offline colaborativo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir entrada por CPF de colaborador Academy no PostgreSQL e reabertura offline por CPF em dispositivo colaborativo, mantendo passkey como alternativa e removendo OTP/e-mail do runtime normal.

**Architecture:** `POST /api/auth/login` passa a ser a superfície pública normal do perfil `postgresql`, resolve somente a identidade Academy já espelhada no PostgreSQL e emite a sessão opaca existente. Após essa sessão, a PWA salva um grant offline assinado, indexado por SHA-256 do CPF canônico; offline, o grant é novamente validado antes de criar uma sessão apenas em memória. O cofre PRF/passkey continua isolado e pode coexistir com o grant colaborativo.

**Tech Stack:** Java 21, Spring Boot 3.5, JDBC/PostgreSQL, React 19, TypeScript, Vite PWA, IndexedDB/idb, Vitest, JUnit 5, Mockito, Testcontainers PostgreSQL.

## Global Constraints

- Academy é a única origem de colaborador/CPF; Zeladoria é origem de ativos, não de identidade.
- O login público nunca consulta MySQL: usa `colaborador` + `auth_identity` no PostgreSQL e seus candidatos HMAC atuais/anterior.
- CPF, e-mail, OTP, PIN e senha não aparecem no `localStorage`, `sessionStorage`, URL, logs ou mensagens de erro. O grant local guarda somente SHA-256 do CPF canônico; esse hash é índice de aparelho colaborativo, não segredo.
- Normal `postgresql` publica somente `POST /api/auth/login` e os dois endpoints de autenticação passkey; OTP fica exclusivo de `postgresql-activation`.
- Cookies opacos/CSRF, autorização por escopo, expiração e assinatura do offline grant permanecem obrigatórios. PWA e API devem usar `/api` na mesma origem.
- A falha de atualizar o grant offline jamais cancela uma sessão online recém-criada; CPF offline só abre um grant local assinado, válido e correspondente.
- Não criar colaboradores, obras, passkeys, dados de demonstração ou bypass de prontidão para fazer o preview parecer pronto.

---

### Task 1: Expor CPF direto no PostgreSQL com limites persistentes

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/DirectCpfLoginPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthLoginRateLimiter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/EmailOtpAuthenticationPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpSecurityConfiguration.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeIssuer.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryConfiguration.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryDispatcher.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryAfterCommitListener.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuard.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/AuthLoginRateLimiterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/DirectCpfLoginPolicyTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicyTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAcademyDirectCpfLoginIT.java`

**Interfaces:**
- `AuthLoginRateLimiter.check(String cpfRaw, String clientIp)` must execute before `AuthService.autenticarPorCpf`; it uses `AuthRateLimitStore` and `CpfLookupDigestService.challengeLookup`, never the JVM-only map.
- Normal PostgreSQL exact public POST set is `"/api/auth/login"`, `"/api/auth/passkeys/authentication/options"`, and `"/api/auth/passkeys/authentication/verify"`.
- `AuthController.login(LoginRequest, HttpServletRequest, HttpServletResponse)` returns the existing safe `AuthSessionResponse`, emits no CPF/e-mail/token body fields, and returns the existing generic rejection for malformed/unknown/inactive CPF.

- [ ] **Step 1: Write failing direct-login policy and controller tests**

Replace the normal-PostgreSQL `410 Gone` expectation with a successful direct CPF session case. Add one test that sets the limiter to reject and asserts `429`, no `autenticarPorCpf`, no `sessions.issue`, and no cookie write. Add a normal-runtime OTP rejection case while preserving the activation OTP case.

```java
doNothing().when(rateLimiter).check(eq("11144477735"), anyString());
mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"cpf\":\"111.444.777-35\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.cpf").doesNotExist())
    .andExpect(jsonPath("$.email").doesNotExist());
```

- [ ] **Step 2: Run backend unit tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest='AuthControllerTest,DirectCpfLoginPolicyTest,AuthLoginRateLimiterTest,AuthPublicEndpointPolicyTest,AuthSessionFilterTest,CsrfRequestFilterTest,PostgresqlRuntimeReadinessGuardTest' test
```

Expected: FAIL because normal PostgreSQL still disables/directly hides CPF, allows normal OTP, and the login limiter is not wired/persistent.

- [ ] **Step 3: Implement the minimal normal PostgreSQL boundary**

Make `DirectCpfLoginPolicy` enabled only when `postgresql` is active and `postgresql-activation` is not. Make `AuthPublicEndpointPolicy` expose the three exact normal paths above, and only exact OTP paths in activation. Make `EmailOtpAuthenticationPolicy.requireEnabled()` throw outside activation. Change `AuthController` to use `Optional<EmailOtpChallengeService>` so normal PostgreSQL can start without an OTP service; request/verify obtain it only after the activation policy succeeds.

Give the OTP configuration, challenge issuer/service, and delivery executor/listener the profile expression `!postgresql | postgresql-activation`. This keeps legacy and activation behavior intact but removes the normal PostgreSQL OTP HMAC/SMTP dependency. Keep `ClientAddressResolver`, `PostgresqlRateLimitBucketRepository`, and the new direct CPF limiter available in normal PostgreSQL.

Refactor `AuthLoginRateLimiter` to depend on `AuthRateLimitStore` and `CpfLookupDigestService`; derive fixed 64-hex bucket keys from a domain-separated SHA-256 of `challengeLookup(cpfRaw)` material, canonical client IP, and the global label. Consume source, global, and protected-identifier buckets with `cortex.auth.login-rate-limit.{max-requests,global-max-requests,window-seconds}`. Add those properties to `application.yml` with bounded defaults. Inject the limiter and `ClientAddressResolver` into `AuthController`; invoke `check` before identity resolution.

Change PostgreSQL readiness from verified e-mail ALFA to one active Academy `auth_identity` with current HMAC material, so the runtime does not require an irrelevant e-mail proof.

- [ ] **Step 4: Run focused backend tests and verify GREEN**

Run the command from Step 2. Expected: PASS, normal PostgreSQL CPF login is public and rate-limited, normal OTP is not accessible, and activation OTP remains isolated.

- [ ] **Step 5: Run PostgreSQL integration proof**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it -DforkCount=1 -DreuseForks=true \
  -Dit.test='PostgresqlAcademyDirectCpfLoginIT,PostgresqlCortexRuntimeIT,PostgresqlCleanStartFlowIT,PostgresqlRateLimitBucketRepositoryIT' verify
```

Expected: PASS; a synthetic active Academy identity issues a resolvable opaque session using PostgreSQL only, and runtime activation does not publish OTP in normal mode.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth \
  apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuard.java \
  apps/api/src/main/resources/application.yml apps/api/src/test/java/com/projeto/cortex
git commit -m "feat(auth): enable canonical CPF login on PostgreSQL"
```

### Task 2: Persist e validar grants colaborativos de CPF na PWA

**Files:**
- Modify: `apps/web/src/features/auth/offlineVault.types.ts`
- Modify: `apps/web/src/features/auth/offlineVault.ts`
- Modify: `apps/web/src/features/auth/offlineVaultRepository.ts`
- Create: `apps/web/src/features/auth/collaborativeOfflineGrant.ts`
- Create: `apps/web/src/features/auth/collaborativeOfflineGrant.test.ts`
- Create: `apps/web/src/features/auth/offlineVaultRepository.test.ts`
- Modify: `apps/web/src/features/auth/authApi.ts`
- Modify: `apps/web/src/features/auth/authApi.test.ts`
- Modify: `apps/web/src/features/auth/authService.ts`
- Modify: `apps/web/src/features/auth/authService.test.ts`

**Interfaces:**
- `OfflineCpfGrantMetadata` contains `{ key, versao: 1, cpfHash, ownerId, scopeFingerprint, signedGrant, serverKeyFingerprint, atualizadoEm }`; no raw CPF is persisted.
- `saveCollaborativeOfflineGrant(cpf, signedGrant)` validates the same signed claims as the passkey vault, hashes the canonical CPF with SHA-256, and persists it in `cpf_grants`.
- `unlockCollaborativeOfflineGrant(cpf, metadata)` rejects hash mismatch, altered signature, expiry, malformed scope, or fingerprint mismatch; only then calls `setOfflineSession`.
- `autenticarPorCpf(cpf)` sets online session first, then best-effort obtains `/auth/offline-grant`; callers receive a nonfatal `offlineGrant` status.

- [ ] **Step 1: Write failing grant storage and unlock tests**

Create synthetic signed-grant fixtures matching `offlineVault.test.ts`. Assert a canonical CPF saves no plaintext CPF, a matching CPF produces the scoped offline session, a different CPF leaves it empty, and expired/tampered grants reject.

```ts
await saveCollaborativeOfflineGrant("11144477735", fixture.grant);
await expect(unlockCollaborativeOfflineGrant(
  "11144477734", metadata,
)).rejects.toThrow("CPF não corresponde");
expect(getSession()).toBeNull();
```

Add repository upgrade coverage from IndexedDB version 1: existing `vaults` remain readable and a new `cpf_grants` store can coexist. In `authService.test.ts`, assert a failed grant refresh leaves `getSession()` equal to the online profile.

- [ ] **Step 2: Run frontend auth tests and verify RED**

Run:

```bash
npm --prefix apps/web test -- --run \
  src/features/auth/collaborativeOfflineGrant.test.ts \
  src/features/auth/offlineVaultRepository.test.ts \
  src/features/auth/authApi.test.ts \
  src/features/auth/authService.test.ts
```

Expected: FAIL because collaborative metadata/functions/API parsing do not exist and direct login does not save a grant.

- [ ] **Step 3: Implement the isolated collaborative-grant module**

Extract only reusable signed-grant verification and offline-session activation from `offlineVault.ts`; retain AES-GCM/PRF behavior unchanged. Bump the vault database to version 2 and add `cpf_grants` with indexes by update time and owner. Add `fetchOfflineGrant()` that validates the exact signed envelope returned by the session-authenticated endpoint. In `autenticarPorCpf`, call `setSession(profile)` before the best-effort grant request and return `{ profile, offlineGrant: "READY" | "UNAVAILABLE" }`.

- [ ] **Step 4: Run focused frontend tests and verify GREEN**

Run the command from Step 2. Expected: PASS; passkey vault records still decrypt as before and CPF grants cannot create a local session without an exact valid match.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/features/auth/offlineVault.types.ts \
  apps/web/src/features/auth/offlineVault.ts \
  apps/web/src/features/auth/offlineVaultRepository.ts \
  apps/web/src/features/auth/collaborativeOfflineGrant.ts \
  apps/web/src/features/auth/authApi.ts apps/web/src/features/auth/authService.ts \
  apps/web/src/features/auth/*test.ts apps/web/src/features/auth/*test.tsx
git commit -m "feat(auth): cache signed CPF grants for collaborative offline use"
```

### Task 3: Trocar a tela por CPF direto e compor os dois desbloqueios offline

**Files:**
- Modify: `apps/web/src/App.tsx`
- Modify: `apps/web/src/App.offlineUnlock.test.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.behavior.test.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.authPolicy.test.ts`
- Modify: `apps/web/src/features/auth/LoginPage.css`
- Modify: `apps/web/src/features/auth/OfflineUnlockPage.tsx`
- Modify: `apps/web/src/features/auth/OfflineUnlockPage.css`
- Modify: `apps/web/src/features/auth/DeviceSecurityPage.policy.test.ts`

**Interfaces:**
- `App` loads both latest passkey-vault metadata and latest CPF-grant metadata before choosing unauthenticated/offline UI.
- `LoginPage` online primary submit calls `autenticarPorCpf(onlyDigits(cpf))`; no OTP state, code field, e-mail copy, or `emailOtpApi` import remains.
- Offline CPF submit calls `unlockCollaborativeOfflineGrant`; passkey unlock stays available when a PRF vault exists.

- [ ] **Step 1: Write failing behavior and policy tests**

Replace OTP expectations with direct CPF expectations, including the primary button and nonfatal cache warning.

```tsx
await user.click(screen.getByRole("button", { name: "Entrar" }));
expect(mocks.autenticarPorCpf).toHaveBeenCalledWith("11144477735");
expect(screen.queryByText(/código|e-mail/i)).not.toBeInTheDocument();
```

Add App cases for: CPF grant only offline, passkey vault only offline, both records together, and no record. Add OfflineUnlockPage cases for matching/mismatching CPF and valid passkey fallback. Update the policy test to assert there is no OTP/e-mail/PIN/raw-CPF persistence and exactly one `authenticateWithPasskey(cpf)` alternative.

- [ ] **Step 2: Run focused UI tests and verify RED**

Run:

```bash
npm --prefix apps/web test -- --run \
  src/features/auth/LoginPage.behavior.test.tsx \
  src/features/auth/LoginPage.authPolicy.test.ts \
  src/App.offlineUnlock.test.tsx \
  src/features/auth/DeviceSecurityPage.policy.test.ts
```

Expected: FAIL because the screen still renders OTP/code flow and App only considers a passkey vault.

- [ ] **Step 3: Implement the focused UI replacement**

Remove `LoginStep`, OTP fields/refs/state/functions, `emailOtpApi` imports, and OTP copy. Preserve existing visual lockup, responsiveness, keyboard focus, error handling, passkey secondary button, and direct form validation. Make App pass both metadata forms to a unified offline-capable access surface instead of routing blindly to a passkey-only page. Do not render stored name, CPF, or scope before the user succeeds.

- [ ] **Step 4: Run focused UI tests and verify GREEN**

Run the command from Step 2. Expected: PASS; online uses direct CPF, offline only grants matching CPF/signed scope, and passkey remains usable.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/App.tsx apps/web/src/App.offlineUnlock.test.tsx \
  apps/web/src/features/auth/LoginPage.tsx \
  apps/web/src/features/auth/LoginPage.css \
  apps/web/src/features/auth/LoginPage.behavior.test.tsx \
  apps/web/src/features/auth/LoginPage.authPolicy.test.ts \
  apps/web/src/features/auth/OfflineUnlockPage.tsx \
  apps/web/src/features/auth/OfflineUnlockPage.css \
  apps/web/src/features/auth/DeviceSecurityPage.policy.test.ts
git commit -m "feat(web): use CPF access with collaborative offline unlock"
```

### Task 4: Corrigir a origem local e atualizar o contrato de deploy

**Files:**
- Modify: `apps/web/package.json`
- Modify: `apps/web/scripts/verify-stavia-boundary.mjs`
- Modify: `compose.local.yml`
- Modify: `scripts/dev/run-api.sh`
- Modify: `apps/api/src/main/resources/application-local.yml`
- Modify: `docs/dev-runbook.md`
- Modify: `docs/deploy-checklist.md`
- Modify: `docs/production-runbook.md`
- Modify: `docs/verification/2026-07-26-cpf-direct-collaborative-offline.md`
- Test: `apps/web/src/lib/api/apiClient.test.ts`

**Interfaces:**
- Local and compose builds embed `VITE_CORTEX_API_BASE_URL=/api`; Vite's proxy target is configured only by `CORTEX_API_TARGET`.
- Compose supports `CORTEX_WEB_PORT` and `CORTEX_API_PORT` loopback overrides, defaults to 5173/8081, and derives CORS/WebAuthn allowed origin from the web port.
- `run-api.sh` no longer requires an OTP secret for normal PostgreSQL and reports the exact health origin/port; activation retains its OTP secret requirement.

- [ ] **Step 1: Write failing same-origin package/config tests**

Extend the existing API-client/static contract test to assert the local build uses root-relative `/api` and does not embed `127.0.0.1:8080/api`. Add a script/config source test that requires `CORTEX_WEB_PORT`/`CORTEX_API_PORT` interpolation and no normal-runtime `CORTEX_AUTH_OTP_HMAC_KEY_FILE` prerequisite.

- [ ] **Step 2: Run the runtime-contract tests and verify RED**

Run:

```bash
npm --prefix apps/web test -- --run src/lib/api/apiClient.test.ts
node apps/web/scripts/verify-stavia-boundary.mjs --source
```

Expected: FAIL because `build:local` embeds an absolute host and current local scripts require/advertise normal OTP.

- [ ] **Step 3: Implement same-origin scripts and documentation**

Change `build:local`/`build:compose` to `VITE_CORTEX_API_BASE_URL=/api`; retain the target only for Vite `server`/`preview` proxy. Update verifier script contracts. Parameterize Compose loopback mappings and local CORS/WebAuthn origin without weakening `apiEndpoint.ts`, cookies, or CSRF. Remove normal OTP secret from API/Compose requirements while leaving activation explicit. Document exact same-worktree startup and that a different `localhost` port is a different browser origin/session.

- [ ] **Step 4: Run final automated gates**

Run:

```bash
npm --prefix apps/web test -- --run
npm --prefix apps/web run lint
VITE_CORTEX_AUTH_MODE=postgresql VITE_CORTEX_API_BASE_URL=/api npm --prefix apps/web run build
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it -DforkCount=1 -DreuseForks=true verify
git diff --check
```

Expected: all commands exit 0. Record test counts, build result, PostgreSQL result, secret scan, and any external runtime dependency not locally configured in the verification document.

- [ ] **Step 5: Run same-worktree localhost smoke and record evidence**

Start API from this worktree on a free loopback port, then start Vite with `CORTEX_API_TARGET=http://127.0.0.1:<api-port>` and root-relative API base. Verify `GET /api/health` through the PWA origin and load the login page. Do not claim a real Academy login/passkey assertion unless the real database/source credentials are configured. Stop only processes launched for this verification.

- [ ] **Step 6: Commit**

```bash
git add apps/web/package.json apps/web/scripts/verify-stavia-boundary.mjs \
  compose.local.yml scripts/dev/run-api.sh apps/api/src/main/resources/application-local.yml \
  docs/dev-runbook.md docs/deploy-checklist.md docs/production-runbook.md \
  docs/verification/2026-07-26-cpf-direct-collaborative-offline.md \
  apps/web/src/lib/api/apiClient.test.ts
git commit -m "fix(runtime): serve Cortex auth through one local origin"
```

## Plan self-review

- Task 1 covers canonical Academy identity, normal/activation route separation, rate limiting, readiness, cookies, CSRF, and PostgreSQL proof.
- Task 2 covers the new local grant boundary without altering PRF encryption or data namespaces.
- Task 3 covers the actual customer-visible removal of OTP/e-mail plus both offline paths.
- Task 4 covers the screenshot's host mismatch and deployment/local evidence.
- No task queries Academy/Zeladoria MySQL on a browser request or fabricates operational data.

# Córtex CPF + passkey PostgreSQL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make CPF identify the Córtex collaborator and a verified passkey assertion the only action that can create a normal PostgreSQL session.

**Architecture:** The options route accepts a CPF in its JSON body, performs a read-only HMAC lookup, and persists only the resolved collaborator UUID in a one-use challenge. It deliberately retains discoverable WebAuthn options, so it never returns a collaborator-specific credential inventory. Verification refuses every null-bound or owner-mismatched challenge before session issue.

**Tech Stack:** React, TypeScript, Vite, Java 21, Spring Boot, Spring JDBC, PostgreSQL, Yubico WebAuthn, JUnit, Mockito, Vitest, Testcontainers.

## Global Constraints

- CPF is an identifier, never proof of login.
- Normal `postgresql` exposes passkey options/verify only; `postgresql-activation` remains e-mail-OTP only.
- Do not log, store, echo, or put a raw CPF in a URL; DTO `toString()` must redact it.
- Preserve opaque cookie, CSRF, exact RP/origin, 300-second single-use challenge, signature counter, and offline-vault contracts.
- Keep assertion options discoverable; no `allowCredentials` or user enumeration before authentication.
- Do not create bootstrap data, a passkey, or an ALFA merely to make local runtime appear ready.

---

### Task 1: Add a read-only CPF-to-passkey challenge boundary

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/CpfPasskeyAuthenticationRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/CpfPasskeyIdentityLookup.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnControllerTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/AuthLogRedactionTest.java`

**Interfaces:**
- `CpfPasskeyAuthenticationRequest(String cpf)` returns `CpfPasskeyAuthenticationRequest[cpf=REDACTED]` from `toString()`.
- `CpfPasskeyIdentityLookup.resolveOrDecoy(String cpf)` returns a canonical active collaborator ID only when it has active/non-deleted ALFA/BETA identity, `ATIVA` status, and an unrevoked passkey. It uses `CpfLookupDigestService.challengeLookup(cpf)` only and performs no HMAC upgrade, legacy SHA lookup, or identity write.
- `WebAuthnService.startCpfBoundAuthentication(String cpf)` always returns generic discoverable options. It writes a canonical collaborator ID only when lookup succeeds; unknown/invalid input writes a null-bound decoy challenge.
- `WebAuthnService.finishCpfBoundAuthentication(String challengeId, JsonNode credential)` never accepts a null-bound challenge.

- [ ] **Step 1: Write failing tests for request redaction and options input**

Add `AuthLogRedactionTest` coverage:

```java
assertThat(new CpfPasskeyAuthenticationRequest("52998224725").toString())
    .contains("REDACTED")
    .doesNotContain("52998224725");
```

In `WebAuthnControllerTest`, post `{"cpf":"529.982.247-25"}` to options, expect `200` and `Cache-Control: no-store`, and verify `webAuthn.startCpfBoundAuthentication("529.982.247-25")`. Add a `429` case proving the rate limiter runs before the service.

- [ ] **Step 2: Run the new tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=AuthLogRedactionTest,WebAuthnControllerTest test
```

Expected: compilation failure because the request DTO and CPF-bound service method do not exist.

- [ ] **Step 3: Write failing service tests for canonical and decoy challenges**

In `WebAuthnServiceTest`, use a mock `CpfPasskeyIdentityLookup` and existing mock engine. For a canonical result, assert:

```java
verify(repository).createChallenge(
    CHALLENGE_ID, COLLABORATOR_ID,
    WebAuthnCeremony.AUTHENTICATION,
    started.challenge(), started.requestJson(), 300
);
verify(engine).startAuthentication();
```

For invalid, unknown, inactive, or no-passkey lookup, assert the same public response shape and a challenge with `null` owner. Add verification cases proving that a null owner or an owner different from verified collaborator, user handle, or credential owner produces `401`, never records authentication, and never returns an identity.

- [ ] **Step 4: Run the service test and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=WebAuthnServiceTest test
```

Expected: FAIL because current authentication stores a null owner for every challenge and accepts a verified credential without a CPF-bound owner check.

- [ ] **Step 5: Implement the minimum safe boundary**

Create a read-only `CpfPasskeyIdentityLookup` that obtains fixed-shape protected digests from `CpfLookupDigestService.challengeLookup`, queries active/non-deleted ALFA/BETA collaborators with `auth_identity.status = 'ATIVA'` and at least one unrevoked WebAuthn credential, and returns at most one UUID. Use the same decoy material for invalid inputs. Do not call `AuthIdentityRepository.findActiveByCpf`, which can upgrade persisted HMAC material.

Make options call `startCpfBoundAuthentication`. Start the existing discoverable engine ceremony for both canonical and decoy results, then persist canonical UUID or null owner. Make `finishCpfBoundAuthentication` consume the challenge, reject null owner before parsing, and require `challenge.collaboratorId`, verified collaborator, user-handle owner, and persisted credential owner to be identical before `recordAuthentication`. Inject `AuthSessionProfileResolver` into the controller and call `requireEligibleForSessionIssue` plus `profileForIssuedSession` around passkey-issued sessions, exactly as the OTP controller path does.

- [ ] **Step 6: Run the focused ceremony suite and verify GREEN**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=AuthLogRedactionTest,WebAuthnControllerTest,WebAuthnServiceTest,YubicoWebAuthnCeremonyEngineTest test
```

Expected: PASS. `YubicoWebAuthnCeremonyEngineTest` still proves that options omit `allowCredentials`.

---

### Task 2: Harden public request handling and PostgreSQL session eligibility

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnPreMvcFilter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnRateLimiter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthSessionRepository.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnPreMvcFilterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnRateLimiterTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicyTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthSessionRepositoryIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlCpfPasskeyIdentityLookupIT.java`

**Interfaces:**
- All public passkey POST bodies are bounded and cached before MVC parsing.
- Options first consume the existing IP/global bucket; after body parsing, the controller consumes a protected identifier bucket based on `CpfLookupDigestService.challengeLookup`, never raw CPF.
- Normal PostgreSQL allows passkey options/verify, blocks normal OTP and direct CPF login; activation allows only e-mail OTP.
- `PostgresqlAuthSessionRepository` resolves an active passkey-authenticated identity without `email_autenticacao`.

- [ ] **Step 1: Write failing pre-MVC and per-identifier rate-limit tests**

Extend `WebAuthnPreMvcFilterTest` with an oversized options body test that expects `413`, `no-store`, no MVC dispatch, and one options rate-limit call. Extend `WebAuthnRateLimiterTest` with an identifier-specific bucket assertion: two different protected lookup materials create distinct HMAC bucket digests; neither raw CPF nor decoy source appears in any bucket argument.

- [ ] **Step 2: Run the rate-limit tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=WebAuthnPreMvcFilterTest,WebAuthnRateLimiterTest test
```

Expected: FAIL because options bodies are not bounded/cached and no protected identifier bucket exists.

- [ ] **Step 3: Write failing PostgreSQL policy/session tests**

Create `AuthPublicEndpointPolicyTest` using `MockHttpServletRequest`. Assert normal PostgreSQL exposes exactly the two passkey authentication paths and blocks OTP/direct CPF. Assert activation exposes bounded OTP and blocks passkey. Invert the second `PostgresqlAuthSessionRepositoryIT` assertion so an `ATIVA` identity with `email_autenticacao = NULL` resolves its opaque session.

Create `PostgresqlCpfPasskeyIdentityLookupIT` from `PostgresqlAuthPersistenceTestSupport`. Insert one protected HMAC identity and one unrevoked WebAuthn credential, then assert lookup returns its UUID. Independently assert inactive, `BLOQUEADA`, revoked/no-passkey, and two-owner HMAC states return the decoy/empty result; the test must use only synthetic CPF/HMAC material and never a real secret.

- [ ] **Step 4: Run policy and PostgreSQL regression tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=AuthPublicEndpointPolicyTest,WebAuthnPreMvcFilterTest,WebAuthnRateLimiterTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlAuthSessionRepositoryIT verify
```

Expected: FAIL because normal PostgreSQL currently exposes OTP, options parsing is unbounded, and a null authentication e-mail invalidates the session.

- [ ] **Step 5: Implement explicit boundaries**

Treat options as a bounded ceremony body in `WebAuthnPreMvcFilter` and replay the cached body to MVC. Add an identifier-bucket operation to `WebAuthnRateLimiter` that accepts protected `AuthChallengeLookupMaterial`, derives only HMAC bucket digests, and consumes it after IP/global acceptance. In `WebAuthnController`, call that method only after it reads the bounded CPF request.

In `AuthPublicEndpointPolicy`, normal PostgreSQL permits only exact passkey options/verify; activation retains exact OTP paths. Keep `DirectCpfLoginPolicy` as defense in depth but do not publish its route. Remove only `identity.email_autenticacao IS NOT NULL` from the PostgreSQL session resolver; preserve all identity/role/expiry/revocation checks.

- [ ] **Step 6: Run the focused policy, filter, and persistence suite and verify GREEN**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=AuthPublicEndpointPolicyTest,AuthSessionFilterTest,CsrfRequestFilterTest,WebAuthnEndpointBoundaryTest,WebAuthnPreMvcFilterTest,WebAuthnRateLimiterTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlAuthSessionRepositoryIT verify
```

Expected: PASS; normal Postgres has no OTP/direct-CPF public path and an active passkey identity does not require e-mail to keep its session.

---

### Task 3: Replace the normal frontend with one CPF + passkey action

**Files:**
- Modify: `apps/web/src/features/auth/passkeyApi.ts`
- Modify: `apps/web/src/features/auth/passkeyApi.test.ts`
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.css`
- Modify: `apps/web/src/features/auth/LoginPage.authPolicy.test.ts`
- Modify: `apps/web/src/App.tsx`
- Create: `apps/web/src/App.authPolicy.test.ts`

**Interfaces:**
- `authenticateWithPasskey(cpf: string): Promise<AuthProfile>` sends `{ cpf }` only to options; assertion verify contains no CPF.
- `LoginPage` validates CPF then calls `authenticateWithPasskey(cpf)` from one form submit action.
- Normal unauthenticated `App` renders `LoginPage`; `main.tsx` keeps activation-only routing separate.

- [ ] **Step 1: Write failing transport/UI policy tests**

Change the passkey test to call `authenticateWithPasskey("529.982.247-25")`; assert the first fetch body equals `JSON.stringify({ cpf: "529.982.247-25" })` and the verify call never contains that CPF. Change `LoginPage.authPolicy.test.ts` to assert no `autenticarPorCpf`, exactly `authenticateWithPasskey(cpf)`, `Confirmar com passkey`, and no public OTP/e-mail/PIN language. Add `App.authPolicy.test.ts` requiring `LoginPage` and rejecting `EmailOtpAccessForm` and `PostgresqlAccessPage` in `App.tsx`.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
npm --prefix apps/web test -- passkeyApi.test.ts LoginPage.authPolicy.test.ts App.authPolicy.test.ts
```

Expected: FAIL because options has no CPF body, LoginPage imports direct CPF login, and App renders its OTP page in PostgreSQL mode.

- [ ] **Step 3: Implement the one-action UI**

Pass CPF only to options in `passkeyApi`. Remove `autenticarPorCpf` and the secondary passkey button from `LoginPage`; the primary submit button becomes `Confirmar com passkey`, and supporting copy says CPF identifies the collaborator while passkey confirms access. Retain the official lockup, black/green composition, focus behavior, responsive card, and primary yellow action. Remove only dead secondary-action and `.postgresql-access*` CSS. Remove `EmailOtpAccessForm` and `PostgresqlAccessPage` from `App`; always return `LoginPage` for a normal unauthenticated app.

- [ ] **Step 4: Run frontend suite and build to verify GREEN**

Run:

```bash
npm --prefix apps/web test -- passkeyApi.test.ts LoginPage.authPolicy.test.ts App.authPolicy.test.ts cortexAuthMode.test.ts ActivationPage.test.ts
npm --prefix apps/web run build
```

Expected: PASS. Normal frontend offers only CPF + passkey while the separate activation bootstrap remains tested.

---

### Task 4: Cross-layer verification and evidence

**Files:**
- Create: `docs/verification/2026-07-24-cpf-passkey-postgresql.md`

- [ ] **Step 1: Run cross-layer regressions**

Run the focused API suites from Tasks 1–2, the focused web suite from Task 3, and `npm --prefix apps/web run build`. Record exact results.

- [ ] **Step 2: Verify the real normal UI without seeding data**

Run the Vite app in `VITE_CORTEX_AUTH_MODE=postgresql` on a free loopback port and inspect `/financeiro`. Confirm the login shows CPF and `Confirmar com passkey`, contains no e-mail OTP, and is responsive. If API startup remains blocked by missing protected bootstrap data/configuration, record that blocker; do not bypass or seed it.

- [ ] **Step 3: Review changed sources for secret and authorization regressions**

Run `git diff --check`, scan changed auth/frontend/docs files for secret-shaped literals, and inspect the staged diff. Confirm raw CPF appears only as field/type names and synthetic test values, never production configuration.

- [ ] **Step 4: Write evidence and commit**

Write command results, browser result, PostgreSQL integration status, and known local-data limitation to the verification document. Stage only the reviewed changed files and commit with `feat(auth): require passkey after CPF identification`.

## Plan self-review

- Tasks 1–2 cover server-side binding, decoys, public-route isolation, request bounds, rate limits, log redaction, and session lifecycle.
- Task 3 covers the only normal visual flow without disturbing activation.
- Task 4 requires test, build, browser, database, secret, and evidence gates before handoff.

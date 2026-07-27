# CPF Academy OTP Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide CPF + e-mail OTP as the primary PostgreSQL login, with passkey as a secondary online option and PRF passkey retained for protected offline unlock.

**Architecture:** A profile-specific PostgreSQL CPF normalizer and lookup resolve the existing HMAC-protected `auth_identity` record and pass its stored authentication e-mail to the established OTP state machine. The normal runtime allows only its exact challenge/verify routes plus existing passkey routes. The login form sends CPF for the primary OTP request and switches to code verification without retaining CPF or e-mail in persistent state.

**Tech Stack:** Spring Boot 3.5, JDBC/PostgreSQL, Flyway-tested schema, React 19, TypeScript, Vitest, Testcontainers PostgreSQL.

## Global Constraints

- PostgreSQL is canonical; Academy and Zeladoria MySQL are read-only source integrations and are never queried on a public login request.
- CPF alone never creates a production session; `POST /api/auth/login` stays local/test-only.
- Unknown and malformed identifiers must receive generic/decoy OTP behavior and reveal no identity or recipient details.
- Normal PostgreSQL publishes only exact pre-auth routes: OTP challenge/verify and passkey authentication options/verify.
- Offline unlock remains user-verifying passkey PRF plus signed grant only; no CPF, PIN, or code fallback is added.

---

### Task 1: Add the PostgreSQL CPF-to-OTP lookup boundary

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/PostgresqlCpfIdentifierNormalizer.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/PostgresqlCpfOtpIdentityLookup.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlCpfOtpIdentityLookupIT.java`

**Interfaces:**
- Consumes: `CpfNormalizer.requireValid(String)`, `CpfLookupDigestService.candidates(String)`, and `AuthenticationChallengeLookup.find(String)`.
- Produces: one `AuthIdentity` whose `emailAutenticacao` is the already-persisted PostgreSQL recipient, or `Optional.empty()`.

- [ ] **Step 1: Write the failing integration tests**

```java
assertThat(lookup.find("11144477735"))
        .contains(new AuthIdentity(COLLABORATOR_ID, "Pessoa", "alfa@stavias.example", "ALFA"));
assertThat(lookup.find("123")).isEmpty();
```

- [ ] **Step 2: Run the isolated test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=PostgresqlCpfOtpIdentityLookupIT test`

Expected: FAIL because the CPF normalizer/lookup class does not exist.

- [ ] **Step 3: Implement the minimal normalizer and lookup**

```java
public String canonicalize(String identifier) {
    try { return CpfNormalizer.requireValid(identifier); }
    catch (IllegalArgumentException ignored) { return INVALID_VALUE; }
}
```

The lookup must use only HMAC CPF candidates against `auth_identity`, require one active collaborator and valid stored e-mail, and return empty for ambiguity.

- [ ] **Step 4: Run the isolated test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=PostgresqlCpfOtpIdentityLookupIT test`

Expected: PASS.

### Task 2: Publish the safe OTP surface in normal PostgreSQL runtime

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/EmailOtpAuthenticationPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicyTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java`

**Interfaces:**
- Consumes: exact public OTP paths `/api/auth/email/challenges` and `/api/auth/email/challenges/{uuid}/verify`.
- Produces: unauthenticated OTP access in profile `postgresql`, while `POST /api/auth/login` remains non-public and returns 410.

- [ ] **Step 1: Write the failing policy tests**

```java
assertThat(policy("postgresql").isPublicAuthenticationRequest(
        request("POST", EMAIL_CHALLENGES))).isTrue();
assertThat(policy("postgresql").isPublicAuthenticationRequest(
        request("POST", "/api/auth/login"))).isFalse();
```

- [ ] **Step 2: Run the focused policy tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=AuthPublicEndpointPolicyTest,AuthControllerTest,CsrfRequestFilterTest test`

Expected: FAIL because normal PostgreSQL excludes OTP routes.

- [ ] **Step 3: Enable only the normal PostgreSQL OTP path**

```java
this.disabled = false;
```

Apply this only for the existing normal/activation OTP policy, and update the exact route allowlist without broad prefixes or direct CPF session access.

- [ ] **Step 4: Run the focused policy tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=AuthPublicEndpointPolicyTest,AuthControllerTest,CsrfRequestFilterTest test`

Expected: PASS.

### Task 3: Make CPF/OTP primary and passkey secondary in the login UI

**Files:**
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.css`
- Modify: `apps/web/src/features/auth/emailOtpApi.ts`
- Test: `apps/web/src/features/auth/LoginPage.behavior.test.tsx`
- Test: `apps/web/src/features/auth/emailOtpApi.test.ts`

**Interfaces:**
- Consumes: `requestEmailOtpChallenge(cpf)` and `verifyEmailOtpChallenge(challengeId, code)`.
- Produces: a safe parsed `AuthProfile`, stored with `setSession(profile)`, then navigates to `/`.

- [ ] **Step 1: Write the failing UI/API tests**

```tsx
expect(screen.getByRole("button", { name: "Enviar código" })).toBeInTheDocument();
expect(screen.getByRole("button", { name: "Entrar com passkey" })).toBeInTheDocument();
```

```ts
expect(mocks.publicAuthFetch).toHaveBeenCalledWith(
  "/auth/email/challenges",
  expect.objectContaining({ body: JSON.stringify({ identifier: "11144477735" }) }),
);
```

- [ ] **Step 2: Run the focused frontend tests to verify they fail**

Run: `npm test -- --run src/features/auth/LoginPage.behavior.test.tsx src/features/auth/emailOtpApi.test.ts`

Expected: FAIL because normal production presents only passkey and the OTP API validates an e-mail.

- [ ] **Step 3: Implement the minimal CPF/OTP UI flow**

```tsx
await requestEmailOtpChallenge(onlyDigits(cpf));
setPhase("code");
```

Use the existing generic OTP notice and six-digit validator. Keep the passkey button `type="button"` as the secondary action. Do not persist CPF, OTP, e-mail, or a credential.

- [ ] **Step 4: Run the focused frontend tests to verify they pass**

Run: `npm test -- --run src/features/auth/LoginPage.behavior.test.tsx src/features/auth/emailOtpApi.test.ts`

Expected: PASS.

### Task 4: Verify runtime, offline, and deployment contracts

**Files:**
- Modify: `docs/deploy-checklist.md`
- Modify: `docs/production-runbook.md`
- Test: `apps/web/src/App.offlineUnlock.test.tsx`
- Test: `apps/web/src/features/auth/DeviceSecurityPage.policy.test.ts`

**Interfaces:**
- Consumes: the existing PRF vault contract and deployment secret requirements.
- Produces: a documented SMTP/verified-email prerequisite for CPF/OTP and unchanged passkey-only offline handling.

- [ ] **Step 1: Write the failing documentation/contract test assertion**

```ts
expect(loginSource).toContain("Entrar com passkey");
expect(offlineSource).toContain("Não há entrada alternativa por CPF, PIN ou código local.");
```

- [ ] **Step 2: Run the focused contracts to verify the UI assertion fails before implementation**

Run: `npm test -- --run src/features/auth/LoginPage.behavior.test.tsx src/App.offlineUnlock.test.tsx src/features/auth/DeviceSecurityPage.policy.test.ts`

Expected: the new CPF/OTP primary assertion fails before Task 3 implementation.

- [ ] **Step 3: Document SMTP and verified Academy e-mail release conditions**

```markdown
CPF/OTP runtime requires PostgreSQL identity provisioning, SMTP STARTTLS, a mounted SMTP password secret, and a verified `email_autenticacao` for each enabled user.
```

- [ ] **Step 4: Run final gates**

Run: `npm test -- --run && npm run lint && VITE_CORTEX_AUTH_MODE=postgresql VITE_CORTEX_API_BASE_URL=/api npm run build`

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test`

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it -DforkCount=1 -DreuseForks=true verify`

Expected: all suites pass, PostgreSQL integration covers the new lookup, and offline remains PRF/passkey-only.

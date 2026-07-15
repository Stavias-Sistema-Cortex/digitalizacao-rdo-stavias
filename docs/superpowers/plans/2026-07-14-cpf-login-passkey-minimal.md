# CPF Login With Minimal Passkey Implementation Plan

> **Decision update (2026-07-15):** the CPF-specific rate-limit portion of
> Task 2 is superseded. `POST /api/auth/login` no longer injects or calls
> `AuthRateLimiter` and does not return application-level `429`. The limiter is
> retained only for the separate e-mail challenge flow; WebAuthn keeps its own
> limiter. The historical Task 2 steps below document the earlier implementation
> and must not be re-applied.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace pre-access e-mail OTP with direct Academy-linked CPF login while preserving opaque HttpOnly sessions, scoped authorization, passkey/offline security, and a minimal login UI.

**Architecture:** Reuse `AuthIdentityRepository` to locate an eligible collaborator through the existing HMAC/legacy boundary, map the exact Alfa/Beta role, and issue the existing opaque session cookie. The web client sends only CPF, stores only `AuthProfile` in memory, and keeps WebAuthn unchanged behind a secondary `Usar passkey` action.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring MockMvc, JUnit 5, Mockito, React 19, TypeScript, Vitest, Vite, MySQL 8.4.

## Global Constraints

- The public login form contains only CPF, `Entrar`, and a minimal `Usar passkey` action.
- Pre-access authentication must not request, send, verify, or mention e-mail.
- `POST /api/auth/login` sends and accepts only `{ cpf }`; no password, token, e-mail, masked CPF, or hardcoded credential crosses the client boundary.
- Server sessions remain opaque, revocable, HttpOnly, `SameSite=Lax`, and scoped by Alfa/Beta worksite permissions.
- E-mail challenge routes are not public; unauthenticated requests receive `401` in the auth filter.
- CPF does not unlock offline data. Offline access remains passkey-only.
- CPF failures remain generic; the direct CPF endpoint has no application-level rate limit.
- Run backend commands with Java 21.

---

## File Map

- `apps/api/src/main/java/com/projeto/cortex/auth/AuthService.java`: convert eligible CPF lookup into an `AuthenticatedIdentity` without returning CPF or e-mail.
- `apps/api/src/main/java/com/projeto/cortex/auth/LoginRequest.java`: reduce the public contract to `cpf` only.
- `apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimiter.java`: remains scoped to e-mail challenges; it is not part of direct CPF login.
- `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`: issue opaque sessions and cookies for valid CPF requests.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java`: keep CPF/passkey public and remove e-mail challenges from the allowlist.
- `apps/api/src/test/java/com/projeto/cortex/auth/AuthServiceTest.java`: service red/green coverage.
- `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`: controller, cookie, response, and absence of an IP/rate-limit dependency.
- `apps/api/src/test/java/com/projeto/cortex/auth/otp/AuthRateLimiterTest.java`: e-mail challenge bucket coverage only.
- `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java`: e-mail endpoints require a session.
- `apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java`: e-mail endpoints are no longer pre-auth CSRF exemptions.
- `apps/web/src/features/auth/authApi.ts`: add `loginWithCpf(cpf): Promise<AuthProfile>` and remove OTP client operations.
- `apps/web/src/features/auth/authService.ts`: add `autenticarPorCpf(cpf): Promise<AuthProfile>` and preserve in-memory session handling.
- `apps/web/src/features/auth/LoginPage.tsx`: one-step CPF form with minimal passkey action.
- `apps/web/src/features/auth/LoginPage.css`: remove OTP/divider styling and make passkey visually secondary.
- `apps/web/src/features/auth/authApi.test.ts`, `authService.test.ts`, `LoginPage.authPolicy.test.ts`: frontend red/green coverage.

---

### Task 1: Authenticate an eligible CPF without weakening session storage

**Files:**
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/AuthServiceTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/LoginRequest.java`

**Interfaces:**
- Consumes: `AuthIdentityRepository.findActiveByCpf(String)` and `PapelAcesso.fromPersistedExact(String)`.
- Produces: `Optional<AuthenticatedIdentity> AuthService.autenticarPorCpf(String cpfRaw)` and `LoginRequest(String cpf)`.

- [ ] **Step 1: Write failing service tests**

Add tests that construct `AuthService` with a mocked `AuthIdentityRepository`, then assert:

```java
when(identities.findActiveByCpf("111.444.777-35"))
        .thenReturn(Optional.of(new AuthIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintetica",
                "ignored@example.test",
                "BETA"
        )));

assertThat(service.autenticarPorCpf("111.444.777-35"))
        .contains(new AuthenticatedIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintetica",
                PapelAcesso.BETA
        ));
```

Add separate assertions that `Optional.empty()` and persisted role `GAMA` return empty without synthesizing Alfa access.

- [ ] **Step 2: Run the service test and verify RED**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -Dtest=AuthServiceTest test
```

Expected: compilation failure because the current constructor and `autenticarPorCpf(String)` contract do not exist.

- [ ] **Step 3: Implement the minimal service contract**

Implement the constructor and mapping:

```java
public AuthService(AuthIdentityRepository identities) {
    this.identities = identities;
}

public Optional<AuthenticatedIdentity> autenticarPorCpf(String cpfRaw) {
    return identities.findActiveByCpf(cpfRaw).flatMap(identity ->
            PapelAcesso.fromPersistedExact(identity.papelAcesso())
                    .map(role -> new AuthenticatedIdentity(
                            identity.colaboradorId(),
                            identity.nome(),
                            role
                    ))
    );
}
```

Change the request record to:

```java
public record LoginRequest(String cpf) {}
```

- [ ] **Step 4: Run the service test and verify GREEN**

Run `./mvnw -Dtest=AuthServiceTest test` with Java 21.

Expected: `BUILD SUCCESS` and all `AuthServiceTest` cases pass.

- [ ] **Step 5: Commit the service slice**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth/AuthService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/LoginRequest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/AuthServiceTest.java
git commit -m "feat(auth): resolve direct cpf identity"
```

### Task 2: Issue the current opaque session (rate-limit steps superseded)

**Files:**
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/otp/AuthRateLimiterTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimiter.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`

**Interfaces:**
- Consumes: `AuthService.autenticarPorCpf(String)`, `ClientAddressResolver.resolve(HttpServletRequest)`, `AuthSessionService.issue(AuthenticatedIdentity)`, and `AuthCookieService.write(HttpServletResponse, IssuedAuthSession)`.
- Produces: `boolean AuthRateLimiter.allowCpfLogin(String cpf, String clientIp)` and `POST /api/auth/login -> AuthSessionResponse`.

- [ ] **Step 1: Write failing rate-limit and controller tests**

Add a limiter test proving the CPF path uses a distinct global scope:

```java
when(cryptography.bucketDigest("global", "cpf-login"))
        .thenReturn("cpf-global");
assertThat(limiter.allowCpfLogin("11144477735", "203.0.113.10"))
        .isTrue();
verify(cryptography).bucketDigest("global", "cpf-login");
```

Replace the legacy tombstone controller test with these cases:

```java
when(rateLimiter.allowCpfLogin("11144477735", "203.0.113.10"))
        .thenReturn(true);
when(authService.autenticarPorCpf("11144477735"))
        .thenReturn(Optional.of(identity(PapelAcesso.BETA)));
when(sessions.issue(any())).thenReturn(issuedSession());
```

Assert status `200`, `Cache-Control: no-store`, the safe scoped profile, no `token`, no `cpf`, no `email`, and one cookie write. Add isolated cases for malformed CPF (`400`), missing identity (`401`), and denied limiter (`429`) with no session issue.

- [ ] **Step 2: Run focused backend tests and verify RED**

Run with Java 21:

```bash
./mvnw -Dtest=AuthRateLimiterTest,AuthControllerTest test
```

Expected: failures because `allowCpfLogin` and the direct controller flow are absent.

- [ ] **Step 3: Implement scoped limiter and controller flow**

Refactor the limiter to keep `allow(...)` for e-mail and add:

```java
public boolean allowCpfLogin(String identifier, String clientIp) {
    return allow("cpf-login", identifier, clientIp);
}
```

The private scoped method must use the supplied scope in its global, IP, and identifier HMAC inputs.

Inject `AuthService` and `AuthRateLimiter` into `AuthController`. Implement login in this order: canonicalize CPF with `CpfNormalizer.requireValid`, resolve the client IP, enforce `allowCpfLogin`, resolve the identity, issue the session, write the cookie, set `no-store`, and return `AuthSessionResponse.from(identity, expiresAt, allowedObraIds)`.

Map invalid CPF to `400`, limit denial to `429`, and any non-eligible identity to the same `401` message `CPF ou acesso inválido.`.

- [ ] **Step 4: Run focused backend tests and verify GREEN**

Run `./mvnw -Dtest=AuthRateLimiterTest,AuthControllerTest test` with Java 21.

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the public login slice**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java \
  apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimiter.java \
  apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/otp/AuthRateLimiterTest.java
git commit -m "feat(auth): issue opaque session from cpf"
```

### Task 3: Remove e-mail from the pre-auth boundary

**Files:**
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java`

**Interfaces:**
- Consumes: exact method/path checks in `AuthPublicEndpointPolicy`.
- Produces: unauthenticated e-mail challenge requests stop in `AuthSessionFilter` with `401`; CPF/passkey routes remain public.

- [ ] **Step 1: Change filter tests first**

Assert `POST /api/auth/email/challenges` and `POST /api/auth/email/challenges/{id}/verify` invoke the session resolver and return `401` without a valid cookie. Preserve public assertions for `POST /api/auth/login` and both passkey authentication routes.

- [ ] **Step 2: Run filter tests and verify RED**

Run:

```bash
./mvnw -Dtest=AuthSessionFilterTest,CsrfRequestFilterTest test
```

Expected: tests fail because e-mail challenge routes are still public.

- [ ] **Step 3: Remove e-mail challenge patterns from the public policy**

The POST allowlist must contain only:

```java
"/api/auth/login"
"/api/auth/passkeys/authentication/options"
"/api/auth/passkeys/authentication/verify"
```

Keep `OPTIONS`, health/readiness, and existing exact safe-method behavior unchanged. Remove `/api/auth/cpf-filter` from the public GET allowlist because Bloom login remains disabled.

- [ ] **Step 4: Run filter tests and verify GREEN**

Run the same focused Maven command. Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the boundary change**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java \
  apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java
git commit -m "fix(auth): keep email behind authenticated access"
```

### Task 4: Replace the OTP client contract with CPF login

**Files:**
- Modify: `apps/web/src/features/auth/authApi.test.ts`
- Modify: `apps/web/src/features/auth/authApi.ts`
- Modify: `apps/web/src/features/auth/authService.test.ts`
- Modify: `apps/web/src/features/auth/authService.ts`

**Interfaces:**
- Produces: `loginWithCpf(cpf: string): Promise<AuthProfile>` and `autenticarPorCpf(cpf: string): Promise<AuthProfile>`.
- Consumes: `apiFetch`, `parseProfile`, `onlyDigits`, and `setSession`.

- [ ] **Step 1: Write failing frontend API/service tests**

Assert the API request is exact:

```ts
expect(mocks.apiFetch).toHaveBeenCalledWith("/auth/login", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ cpf: "11144477735" }),
});
```

Assert the response parser discards injected `token`, `cpfMascarado`, and `email`, and that `autenticarPorCpf("111.444.777-35")` calls the API with digits only before calling `setSession(profile)`.

- [ ] **Step 2: Run focused Vitest and verify RED**

Run:

```bash
npm test -- src/features/auth/authApi.test.ts src/features/auth/authService.test.ts
```

Expected: failures because CPF login functions do not exist.

- [ ] **Step 3: Implement the minimal client contract**

Replace OTP exports with:

```ts
export async function loginWithCpf(cpf: string): Promise<AuthProfile> {
  const response = await apiFetch("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cpf }),
  });
  const body = await readResponseBody(response);
  if (!response.ok) throw responseError(body, response.status);
  return parseProfile(body);
}
```

In the service:

```ts
export async function autenticarPorCpf(cpf: string): Promise<AuthProfile> {
  const profile = await loginWithCpf(onlyDigits(cpf));
  setSession(profile);
  return profile;
}
```

Keep session initialization and logout behavior unchanged.

- [ ] **Step 4: Run focused Vitest and verify GREEN**

Run the same Vitest command. Expected: both files pass.

- [ ] **Step 5: Commit the client contract**

```bash
git add apps/web/src/features/auth/authApi.ts \
  apps/web/src/features/auth/authApi.test.ts \
  apps/web/src/features/auth/authService.ts \
  apps/web/src/features/auth/authService.test.ts
git commit -m "feat(web): authenticate directly by cpf"
```

### Task 5: Simplify the login page and passkey presentation

**Files:**
- Modify: `apps/web/src/features/auth/LoginPage.authPolicy.test.ts`
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.css`

**Interfaces:**
- Consumes: `autenticarPorCpf`, `authenticateWithPasskey`, `validateLoginForm`, and existing CSS tokens.
- Produces: one-step CPF form and secondary `Usar passkey` action.

- [ ] **Step 1: Write the failing page policy test**

Assert the source contains `autenticarPorCpf`, `Entrar`, `Usar passkey`, and the offline Córtex message. Assert it does not contain `Enviar código`, `Código de acesso`, `Reenviar código`, `one-time-code`, `challenge`, `email`, `e-mail`, or `login__divider`.

- [ ] **Step 2: Run the page test and verify RED**

Run:

```bash
npm test -- src/features/auth/LoginPage.authPolicy.test.ts
```

Expected: failure on OTP text and missing direct CPF action.

- [ ] **Step 3: Implement the one-step page and minimal CSS**

Reduce state to CPF, field errors, `idle | cpf | passkey`, auth error, and online status. On valid submit:

```ts
setStatus("cpf");
try {
  await autenticarPorCpf(cpf);
  window.location.assign("/");
} catch (error: unknown) {
  setStatus("idle");
  setAuthError(errorMessage(error));
  cpfRef.current?.focus();
}
```

Render the primary button label `Entrar`/`Entrando...`, followed directly by the `Usar passkey` button. Remove OTP state/effects/markup and divider/OTP CSS. Style `.login__passkey` as a transparent text action with no border, shadow, card background, or uppercase decoration.

- [ ] **Step 4: Run page and full web checks**

Run:

```bash
npm test -- src/features/auth/LoginPage.authPolicy.test.ts
npm run lint
npm run build
```

Expected: policy test passes, ESLint exits `0`, and Vite build succeeds.

- [ ] **Step 5: Commit the UI slice**

```bash
git add apps/web/src/features/auth/LoginPage.tsx \
  apps/web/src/features/auth/LoginPage.css \
  apps/web/src/features/auth/LoginPage.authPolicy.test.ts
git commit -m "feat(web): simplify cpf login and passkey"
```

### Task 6: Full verification and local browser proof

**Files:**
- Verify only; no production file changes expected.

**Interfaces:**
- Consumes: completed backend/frontend slices.
- Produces: fresh proof that direct CPF, opaque session, workspace access, and minimal passkey UI work together.

- [ ] **Step 1: Run full automated checks**

Backend with Java 21:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw test
```

Frontend:

```bash
npm test -- --run
npm run lint
npm run build
```

Expected: all commands exit `0` with no failing tests.

- [ ] **Step 2: Restart the feature-worktree API and web servers**

Keep MySQL on `3307`, API on `8080`, and web on `5173`. Confirm both process working directories resolve inside `.worktrees/cortex-mensagens-financeiro`.

- [ ] **Step 3: Verify the real HTTP contract**

Use a real active collaborator CPF supplied through a local environment variable, never printed or committed:

```bash
curl -i -c /tmp/cortex-cookie.txt \
  -H 'Content-Type: application/json' \
  --data "{\"cpf\":\"$CORTEX_LOCAL_TEST_CPF\"}" \
  http://127.0.0.1:8080/api/auth/login
curl -b /tmp/cortex-cookie.txt http://127.0.0.1:8080/api/auth/session
rm -f /tmp/cortex-cookie.txt
```

Expected: login `200` with a Set-Cookie header; session `200` contains scoped profile fields and no CPF, e-mail, or token. If no real CPF environment variable is available, report that manual credential proof was not executed and do not invent one.

- [ ] **Step 4: Verify in the in-app browser**

Open `http://127.0.0.1:5173/`. Confirm the login surface contains CPF, `Entrar`, and minimal `Usar passkey`; confirm no e-mail/código copy and no console errors. Do not submit a real CPF unless it is already explicitly authorized for this local test.

- [ ] **Step 5: Final repository check**

Run:

```bash
git status --short --branch
git diff --check
```

Expected: clean worktree on `feat/cortex-mensagens-financeiro`.

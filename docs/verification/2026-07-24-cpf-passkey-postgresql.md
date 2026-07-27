# CPF + passkey PostgreSQL — evidence

> Histórico de uma política anterior. O login normal somente por passkey foi
> substituído em 2026-07-26 por CPF + OTP de e-mail como fluxo principal, com
> passkey como alternativa online e como proteção obrigatória do cofre offline.
> Consulte [a evidência atual](2026-07-26-cpf-otp-primary.md) antes de operar ou
> publicar o ambiente.

Date: 2026-07-24
Scope: `856f0b7..HEAD` on `develop`

## Delivered normal sign-in contract

- The normal PostgreSQL client identifies a collaborator by CPF and offers one
  action: **Confirmar com passkey**.
- CPF is sent only to `POST /auth/passkeys/authentication/options`; assertion
  verification contains only the challenge identifier and WebAuthn credential.
- The server binds the challenge to an eligible ALFA/BETA identity using a
  read-only, fixed-shape HMAC lookup. Unknown, inactive, blocked, no-passkey,
  and ambiguous matches receive a decoy challenge and cannot issue a session.
- Normal PostgreSQL exposes only passkey options and verification. Direct CPF
  login and normal e-mail OTP are not public; activation retains its separate
  e-mail OTP route.
- The same OTP restriction is enforced again in the controller, so an already
  authenticated request cannot bypass the public-route filter and mint a new
  session with e-mail OTP.
- A passkey-authenticated session no longer requires a stored authentication
  e-mail, while status, role, deletion, and revocation checks remain.

## Automated evidence

| Command | Result |
| --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=AuthLogRedactionTest,WebAuthnControllerTest,WebAuthnServiceTest,YubicoWebAuthnCeremonyEngineTest test` | Pass: 25 tests. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=AuthPublicEndpointPolicyTest,AuthSessionFilterTest,CsrfRequestFilterTest,WebAuthnControllerTest,WebAuthnEndpointBoundaryTest,WebAuthnPreMvcFilterTest,WebAuthnRateLimiterTest test` | Pass: 31 tests. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Dtest=AuthControllerTest,AuthLogRedactionTest,AuthPublicEndpointPolicyTest,AuthSessionFilterTest,CsrfRequestFilterTest,WebAuthnControllerTest,WebAuthnEndpointBoundaryTest,WebAuthnPreMvcFilterTest,WebAuthnRateLimiterTest,WebAuthnServiceTest,YubicoWebAuthnCeremonyEngineTest,PostgresqlMinimalLauncherContractTest,CortexApplicationComponentScanTest test` | Pass: 67 tests, including the authenticated-request OTP bypass regression and activation-launcher contract. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Ppostgresql-it -Dtest=PostgresqlLocalRuntimeContractTest -Dit.test=PostgresqlAuthSessionRepositoryIT,PostgresqlCpfPasskeyIdentityLookupIT verify` | Pass: 4 local runtime-contract tests and 4 PostgreSQL 18.4 Testcontainers integration tests; all 17 migrations reached V59. Flyway warns that PostgreSQL 18 is newer than its tested version 17. |
| `npm --prefix apps/web test -- passkeyApi.test.ts LoginPage.authPolicy.test.ts App.authPolicy.test.ts cortexAuthMode.test.ts ActivationPage.test.ts` | Pass: 17 tests in 5 files. |
| `npx tsc -b` and `npx vite build` from `apps/web` | Pass. |
| `git diff --check 856f0b7..HEAD` | Pass. |

The unqualified API suite was also run. Its authentication/revenue tests
continued through the affected area, but `StaviaRuntimeBoundaryTest` has two
existing environment/artifact failures: ignored local `.env` files and old
`.worktrees` content are scanned, and the pre-existing packaged JAR contains
retired assistant classes. This change does not touch either source. The broad
frontend suite likewise reports 650 passing tests and three local-environment
failures described below.

## Browser evidence

The local app was run at `http://127.0.0.1:5175/financeiro` with
`VITE_CORTEX_AUTH_MODE=postgresql`. Desktop inspection confirmed the
black/green Cortex layout, CPF field, one yellow **Confirmar com passkey**
button, no e-mail/OTP controls, keyboard-visible CPF validation, and no
horizontal overflow (`scrollWidth` 1265 at an inner width of 1280).

The local API was deliberately not started: its protected runtime bootstrap is
not configured with a real provisioned collaborator/passkey dataset. No user,
credential, or seed data was added to make the demonstration appear live.

## Security review evidence

- Independent reviews of Tasks 1, 2, and 3 found no blocking issue. A final
  branch review identified the authenticated-session OTP bypass, which is now
  covered by a controller-level regression test; its follow-up review passed
  after the activation launcher was updated explicitly.
- The changed-source secret-pattern scan found only synthetic HMAC strings in
  `PostgresqlCpfPasskeyIdentityLookupIT`; no production credential or database
  secret was introduced.
- Production CPF references are limited to the redacted request DTO, transient
  HMAC lookup/rate-limit flow, and options request. The DTO `toString()`
  explicitly redacts the identifier.
- The full Codex Security diff-scan setup was opened for this range. It awaits
  explicit setup confirmation before its managed exhaustive scan can run, so
  this document does not represent it as completed.

## Known local-environment limitations

`npm --prefix apps/web run build` completes TypeScript and Vite, then its
legacy boundary script fails because ignored root `.env` and `.env.local`
contain legacy brand strings. The same files make the broad frontend test suite
fail one boundary test. Two browser-geometry tests also fail locally because
their spawned browser does not expose a DevTools protocol port within ten
seconds. These ignored environment files and browser-launch conditions are not
part of this diff and were not changed.

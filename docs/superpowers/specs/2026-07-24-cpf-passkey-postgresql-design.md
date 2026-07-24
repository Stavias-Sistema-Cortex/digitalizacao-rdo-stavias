# Córtex PostgreSQL — CPF + passkey design

**Status:** approved by the owner on 2026-07-24.

## Goal

Make CPF the constrained identifier for an online Córtex sign-in and a
verified passkey the only proof that can create an online PostgreSQL session.
The ordinary application must not present e-mail OTP. E-mail OTP remains only
in the isolated initial-activation surface that already exists for bootstrap
and recovery.

## Product decisions

- A valid CPF identifies an existing, active Córtex collaborator; it never
  authenticates that collaborator or creates a session.
- The normal login screen asks for CPF and has one primary action: **Entrar
  com passkey**.
- PostgreSQL normal mode renders the same CPF/passkey screen as the legacy
  mode. It does not render `EmailOtpAccessForm` or copy that calls for an
  institutional e-mail.
- `POST /api/auth/login` remains unavailable in every PostgreSQL web mode and
  is not a normal PostgreSQL public route. Its controller guard remains only
  as defense in depth.
- `postgresql-activation` retains its independently bootstrapped e-mail OTP
  page. It is not a fallback in the normal web application.
- No bootstrap collaborator, passkey, CPF, Academy data, or local test data
  is fabricated to make a runtime appear ready.

## Authentication ceremony

```text
CPF input
  -> protected CPF lookup (HMAC candidates only)
  -> active collaborator bound to a server-side WebAuthn challenge
  -> discoverable WebAuthn assertion
  -> browser passkey assertion
  -> one-use challenge + credential-owner + user-handle verification
  -> opaque session cookie
```

1. The browser validates the CPF locally, sends it only in the body of
   `POST /api/auth/passkeys/authentication/options`, and never places it in a
   URL, local storage, session storage, error message, or client log.
2. The server uses a dedicated read-only PostgreSQL passkey lookup. It builds
   fixed-shape HMAC/decoy material from `CpfLookupDigestService` and resolves
   at most one active, non-deleted ALFA/BETA identity with an active passkey.
   It must not reuse `AuthIdentityRepository.findActiveByCpf`, which can
   upgrade protected lookup material before authentication completes.
3. The server starts an indistinguishable decoy challenge for unknown CPF,
   inactive identity, malformed CPF, or no active passkey. It persists a
   canonical collaborator UUID only for a real lookup; a null-bound decoy is
   unconditionally rejected at verification. This makes options response
   shape independent of account existence.
4. The relying party begins a discoverable assertion without returning a
   collaborator-specific `allowCredentials` list. This avoids disclosing
   credential inventory or account shape before authentication. The claimed
   collaborator remains bound only on the server.
5. The stored WebAuthn authentication challenge persists that collaborator
   UUID. It remains 300 seconds, single-use, and is consumed in its own
   transaction before parsing or signature verification.
6. On verification, the engine must prove user verification, origin/RP
   constraints, signature-counter validity, credential ownership, and user
   handle. The verified collaborator, persisted credential owner, user
   handle, and challenge owner must all be the same collaborator before the
   opaque session is issued.

## Security controls

- Direct CPF login is not publicly routable in normal PostgreSQL; no branch
  may issue a session from `AuthService.autenticarPorCpf` under a PostgreSQL
  profile.
- Both assertion-options and assertion-verify stay behind the existing
  WebAuthn IP rate limiter and pre-MVC rate-limit filter. Options bodies are
  bounded before MVC parsing, and options use a protected identifier bucket
  derived from HMAC/decoy material.
- The authentication controller returns `Cache-Control: no-store` for both
  operations.
- CPF is not echoed, retained in a challenge, added to a session, or written
  to telemetry. Only a resolved collaborator UUID (or a null decoy owner) is
  stored in the challenge.
- The public-route allowlist permits only the two passkey authentication
  endpoints in normal PostgreSQL mode. The e-mail OTP routes are allowlisted
  only in `postgresql-activation` mode.
- CSRF policy, secure opaque-cookie issuance, exact RP ID/origin validation,
  challenge TTL, and existing WebAuthn credential counter updates remain
  unchanged.
- PostgreSQL session resolution accepts an active `auth_identity` after a
  passkey proof; it must not additionally require an e-mail field that is
  irrelevant to this normal passkey-only runtime.

## UI direction

The access page is a gate, not a dashboard: retain the existing black/green
Córtex institutional composition, official logo, clear CPF field, and a
single high-contrast passkey action. The signature moment is the action label
itself: **Confirmar com passkey**, which makes the security boundary legible
without adding a second competing login path. Keyboard focus, reduced motion,
and the existing responsive card behavior remain mandatory.

## Explicit non-goals

- Password authentication, direct CPF authentication, email OTP in normal
  runtime, or any fallback that silently lowers assurance.
- A user-enumeration endpoint for checking whether a CPF has an account or a
  registered passkey.
- Changes to the ALFA bootstrap source, Academy credentials, migrations, or
  operational data.
- Altering the offline vault protocol. Offline unlock remains tied to a
  previously registered passkey and signed grant.

## Verification requirements

1. Backend unit tests prove a valid CPF begins a discoverable challenge bound
   to the resolved collaborator without exposing credential inventory.
2. Tests prove malformed/unknown/no-passkey inputs receive the same options
   contract but can only create null-bound decoy challenges that never issue
   a session.
3. Tests prove verification rejects an assertion when the challenge owner,
   user handle, verified collaborator, or credential owner differ.
4. Public-endpoint policy tests prove normal PostgreSQL permits passkey
   options/verify, keeps direct CPF blocked, and does not publish e-mail OTP;
   activation keeps its e-mail-only boundary.
5. Frontend tests prove the passkey API posts CPF only to the options endpoint
   and that the normal PostgreSQL app renders `LoginPage`, not the OTP page.
6. The login policy test proves there is no direct-CPF call or public OTP
   copy in the normal login screen.
7. Focused Java/TypeScript suites, production build, secret scan, and a
   browser verification run complete before the change is handed off.

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
- `POST /api/auth/login` remains unavailable in every PostgreSQL web mode.
- `postgresql-activation` retains its independently bootstrapped e-mail OTP
  page. It is not a fallback in the normal web application.
- No bootstrap collaborator, passkey, CPF, Academy data, or local test data
  is fabricated to make a runtime appear ready.

## Authentication ceremony

```text
CPF input
  -> protected CPF lookup (HMAC candidates only)
  -> active collaborator with at least one active passkey
  -> WebAuthn assertion options restricted to that collaborator
  -> browser passkey assertion
  -> one-use challenge + credential-owner + user-handle verification
  -> opaque session cookie
```

1. The browser validates the CPF locally, sends it only in the body of
   `POST /api/auth/passkeys/authentication/options`, and never places it in a
   URL, local storage, session storage, error message, or client log.
2. The server normalizes and validates the CPF, then resolves it through
   `AuthIdentityRepository.findActiveByCpf`. That repository uses the
   configured HMAC lookup material and is explicitly an identifier boundary,
   not an authentication result.
3. The server resolves the resulting collaborator as an active WebAuthn
   identity and refuses the ceremony unless at least one active credential is
   present. Unknown CPF, inactive identity, malformed CPF, and no passkey
   receive the same generic unauthorized response. The response must not
   disclose which condition occurred.
4. The relying party begins a username-bound assertion for the collaborator
   UUID. This yields `allowCredentials` for that collaborator rather than a
   discoverable assertion that could select another person’s credential.
5. The stored WebAuthn authentication challenge persists that collaborator
   UUID. It remains 300 seconds, single-use, and is consumed in its own
   transaction before parsing or signature verification.
6. On verification, the engine must prove user verification, origin/RP
   constraints, signature-counter validity, credential ownership, and user
   handle. The verified collaborator, persisted credential owner, user
   handle, and challenge owner must all be the same collaborator before the
   opaque session is issued.

## Security controls

- Direct CPF login remains 410 in PostgreSQL; no branch may issue a session
  from `AuthService.autenticarPorCpf` under a PostgreSQL profile.
- Both assertion-options and assertion-verify stay behind the existing
  WebAuthn IP rate limiter and pre-MVC rate-limit filter.
- The authentication controller returns `Cache-Control: no-store` for both
  operations.
- CPF is not echoed, retained in a challenge, added to a session, or written
  to telemetry. Only the resolved collaborator UUID is stored in the
  challenge.
- The public-route allowlist permits the two passkey authentication endpoints
  in normal PostgreSQL mode. The e-mail OTP routes are allowlisted only in
  `postgresql-activation` mode.
- CSRF policy, secure opaque-cookie issuance, exact RP ID/origin validation,
  challenge TTL, and existing WebAuthn credential counter updates remain
  unchanged.

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

1. Backend unit tests prove a valid CPF begins a challenge bound to the
   resolved collaborator and invokes a username-bound assertion.
2. Tests prove malformed/unknown/no-passkey inputs return the same generic
   failure and do not create a challenge or session.
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

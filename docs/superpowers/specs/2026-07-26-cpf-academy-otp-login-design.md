# CPF Academy OTP Login Design

## Goal

Make the normal Córtex PostgreSQL sign-in usable without a passkey while
retaining a proof of account control: CPF identifies an active Academy-linked
identity and a single-use code is delivered to that identity's verified
authentication e-mail. Passkey remains an explicit secondary online method.

## Decision

The primary online route is **CPF + e-mail OTP**, not CPF possession alone.
CPF is an identifier, not a secret, so `POST /api/auth/login` remains disabled
outside local/test profiles. The OTP lookup and session issuance operate only
on the canonical PostgreSQL Córtex database; Academy is read-only source data
used by the existing import/provisioning flow, never queried during a public
login request.

The login screen first accepts a canonical CPF. It requests an
enumeration-safe e-mail OTP, then accepts a six-digit code. A separate
secondary action starts the existing CPF-bound WebAuthn ceremony. Both paths
issue the same opaque Córtex cookie and safe session profile.

## Runtime boundaries

- `postgresql` enables the exact e-mail OTP public routes and their CSRF
  exemption, but leaves direct CPF-session login disabled.
- `postgresql-activation` keeps its e-mail-address bootstrap flow unchanged.
- In normal PostgreSQL runtime, a CPF lookup uses the existing keyed CPF HMAC
  candidates in `auth_identity`, resolves at most one active Córtex identity,
  and sends the code only to `email_autenticacao` already held in PostgreSQL.
- All OTP responses remain generic. Invalid, missing, blocked, ambiguous, and
  unprovisioned identities create decoy challenges with no recipient.
- The existing OTP rate limits, single-use consumption, expiry, and opaque
  session cookie are retained.

## Offline boundary

Online OTP access does not mint an offline vault. Offline authentication
continues to require a user-verifying WebAuthn PRF result and a signed scoped
grant. After a successful CPF/OTP session, the existing device-security page
can invite the user to register a passkey if they want offline capability.
There is no CPF, PIN, or cached-code offline fallback.

## Validation

- Backend tests prove regular PostgreSQL publishes only the exact OTP and
  passkey authentication routes, not direct CPF login.
- Unit and PostgreSQL integration tests prove a valid CPF resolves through the
  keyed identity lookup, delivery goes only to the stored authentication e-mail,
  and unknown or malformed CPF input remains a decoy flow.
- Frontend tests prove CPF/OTP is the primary visible flow, passkey is
  secondary, profile parsing remains safe, and offline unlock remains
  passkey/PRF-only.
- Full frontend, backend, PostgreSQL integration, container, and secret-scan
  gates are rerun after the change.

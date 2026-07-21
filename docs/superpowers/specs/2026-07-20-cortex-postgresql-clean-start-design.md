# Córtex PostgreSQL Clean-Start Design

**Status:** desenho aprovado em conversa; revisão desta especificação pendente.

**Supersedes for the next delivery:** the proposed legacy-data cutover described
in `2026-07-20-postgresql-stavias-cortex-design.md`. That foundation remains
useful, but Córtex will now start with no legacy Córtex data.

## Goal

Create a clean, canonical Córtex database in PostgreSQL named
`StaviasCortex`, seed one secure initial ALFA identity from the existing
Academy record, and keep MySQL solely as a read-only external integration for
Academy and Zeladoria.

The result of this delivery is a verified, empty PostgreSQL Córtex foundation
with an auditable initial administrator. It is intentionally not a silent
traffic cutover: the general Córtex API remains gated until each MySQL-native
runtime module has been ported and verified in PostgreSQL.

## Product Decisions

- Do not import any existing Córtex rows, offline receipts, sessions, RDOs,
  ontology events, financial records, messages, or attachments.
- Do not use Supabase.
- PostgreSQL is the future and only canonical Córtex operational store. New
  Córtex operational data must not be written to MySQL.
- Academy and Zeladoria remain external MySQL sources. They are never Córtex
  primary data stores and no Córtex migration writes to either source.
- The initial ALFA is resolved from one owner-provided CPF held in a protected
  runtime secret. The CPF is not written to source code, `.env` examples,
  test fixtures, documentation examples, logs, error messages, or Memory
  payloads.
- Academy confirms a source account and supplies its initial email address;
  it does not authenticate with its user password and it is not an identity
  provider for the Córtex runtime.

## Scope of This Delivery

1. Write one PostgreSQL-only Flyway baseline,
   `db/migration-postgresql/V44__postgresql_schema_baseline.sql`, representing
   the final Córtex V44 schema with no operational rows.
2. Add explicit non-web migration and bootstrap modes so Flyway can install
   the baseline before the normal PostgreSQL readiness guard is evaluated.
3. Add an Academy-targeted, read-only lookup for a single active collaborator.
4. Add a one-shot, non-web bootstrap command that creates only the matching
   PostgreSQL collaborator, its protected identity, ALFA role, administrative
   capability, and an audit receipt.
5. Add an activation-only web mode that permits only the strong email-OTP
   endpoints necessary to verify that identity. It must deny general Córtex
   endpoints until normal PostgreSQL runtime readiness is explicitly enabled.
6. Disable the legacy direct-CPF session endpoint in every PostgreSQL web
   profile. A CPF is an identifier, never proof of authentication.
7. Prove the source boundary, bootstrap idempotence, migration correctness,
   and activation gate with automated tests.

## Explicit Non-Goals

- Importing or reconciling legacy Córtex data from MySQL.
- Dual-write, replication, automatic fallback, or a mixed primary-database
  request.
- Rewriting Academy or Zeladoria schemas, credentials, users, or data.
- Exposing the full Córtex operational API before its SQL is PostgreSQL-safe.
- PostGIS. `obra_geometria` remains GeoJSON in `jsonb` until real spatial
  queries require a separate spatial design.
- Storing attachment bytes in PostgreSQL. PostgreSQL owns file metadata,
  permissions, hashes, and a storage key; object bytes remain in the
  configured Córtex storage backend. In particular, the historical
  `importacao_rdo.arquivo_bytes` and `stavia_contexto_obra.arquivo_bytes`
  columns become nullable `storage_key` metadata in the PostgreSQL baseline.
  `bytea` is retained only for small security material that is inherently
  database-resident, such as WebAuthn credential data.

## Architecture

### Data-source boundaries

`spring.datasource` in PostgreSQL profiles points only at `StaviasCortex`.
The existing MySQL connector remains on the classpath only because the
Academy and Zeladoria adapters open their own explicitly configured,
read-only connections under `cortex.sources.academy.*` and
`cortex.sources.zeladoria.*`.

The MySQL account used by each source must also receive database-level
`SELECT`-only grants. `Connection.setReadOnly(true)` is a useful second
control, but it is not the sole write prohibition.

### PostgreSQL baseline

The baseline is a single PostgreSQL migration rather than a replay of the
MySQL V1--V44 history. It creates the final set of tables, foreign keys,
checks, unique constraints, and indexes required by V44, but no product data.
It uses PostgreSQL semantics:

- MySQL JSON becomes `jsonb`.
- Boolean flags become `boolean`.
- WebAuthn binary credential columns become `bytea`; attachment/import bytes
  become a nullable storage key plus the existing metadata and hash fields.
- MySQL auto-increment columns use PostgreSQL identity/sequence semantics.
- MySQL-generated columns, partial uniqueness, and updated-at behavior are
  expressed with PostgreSQL generated columns, partial indexes, and a
  centralized timestamp trigger where needed.
- Existing Java-facing IDs remain textual `char(36)`/`varchar(36)` in this
  delivery; converting all callers to native PostgreSQL UUID types is not an
  incidental schema change.
- `auth_identity` has a partial, case-normalized unique e-mail lookup index
  for the new clean installation. PostgreSQL email OTP queries that index;
  duplicated canonical e-mail ownership fails bootstrap closed rather than
  choosing an identity silently.

Flyway creates its PostgreSQL history table while applying the baseline and
records successful version 44. The process does not copy
`flyway_schema_history` from MySQL.

### Modes and readiness gates

Four deliberately separate modes prevent a partial schema from becoming a
partial Córtex service:

| Mode | Web server | Flyway | Permitted responsibility |
| --- | --- | --- | --- |
| `postgresql-migrate` | no | enabled | Install/validate only the PostgreSQL V44 baseline. |
| `postgresql-bootstrap` | no | disabled after migration | Resolve the configured Academy user and create the one PostgreSQL ALFA identity. |
| `postgresql-activation` | yes, restricted | disabled | Permit only email-OTP activation endpoints and health/readiness diagnostics. |
| `postgresql` | yes | disabled | Reserved normal Córtex runtime, only after baseline, explicit runtime-readiness, and a separately released PostgreSQL-safe vertical slice. |

The existing schema readiness guard applies to activation and normal runtime,
not to the non-web migration mode. A second explicit
`cortex.postgresql.runtime-ready` gate defaults to `false`; it prevents a
baseline-only deployment from serving unported Obras, RDO, Memória, sync,
financeiro, messages, or storage endpoints.

In this clean-start delivery, no general operational vertical slice is marked
PostgreSQL-safe. Consequently, setting `runtime-ready=true` alone must still
fail closed; the activation server is the only PostgreSQL web surface that can
start. A later approved slice must register its route/controller boundary
before the normal profile becomes deployable.

## Initial ALFA Bootstrap

1. An operator creates an owner-only (`0600`) secret file containing the
   supplied CPF and configures only its file path, for example through
   `CORTEX_BOOTSTRAP_ADMIN_CPF_FILE`. The raw value is never placed in an
   argument, log, environment dump, repository file, or source code.
2. The non-web bootstrap process reads and clears the secret bytes in memory.
3. It performs one parameterized Academy lookup for an active source user.
   The lookup returns only the source primary key, display name, validated
   email, active flag, group, and profile metadata needed to seed Córtex. The
   supplied CPF is used only as the protected lookup input and is not returned
   in the source DTO. The adapter never reads a user password or writes to
   MySQL.
4. If no single active user with a valid email is found, the PostgreSQL
   transaction rolls back. No collaborator, identity, capability, or receipt
   is created.
5. In one PostgreSQL transaction, the process creates a collaborator sourced
   from Academy, an `auth_identity` whose CPF lookup uses the existing HMAC
   policy, role `ALFA`, the required administrative-role capability, and an
   idempotency receipt. Raw CPF and full email are excluded from operational
   Memory events.
6. The bootstrap process records a safe operational Memory event identifying
   the bootstrap action, time, actor class, and receipt id, with no sensitive
   source values.
7. A repeated command with the same protected bootstrap receipt is a no-op;
   a different or conflicting source user fails closed.
8. The operator removes the secret file after success.

The existing generic identity provisioner is not reused directly because it
requires an already-existing collaborator. This clean-start bootstrap owns
creation of the first collaborator and identity together.

## Authentication and Activation

Academy does not expose user passwords through the current adapter. The
initial ALFA therefore proves access through the verified Academy email:

1. In `postgresql-activation` mode, the browser can request and verify an
   email OTP using a normalized e-mail identifier; no PostgreSQL OTP lookup
   accepts a CPF.
2. OTP verification changes the PostgreSQL identity from `PENDENTE` to
   `ATIVA` and issues the normal opaque session.
3. WebAuthn passkey enrollment is outside this clean-start delivery and is
   introduced later as a separately verified authentication slice.
4. The legacy `POST /api/auth/login` direct-CPF session path returns a
   generic disabled response in all PostgreSQL profiles. No branch may create
   a PostgreSQL session from CPF alone.
5. The normal runtime readiness check requires at least one active,
   email-verified ALFA identity, explicit runtime readiness, and a separately
   released PostgreSQL-safe vertical slice before the general PostgreSQL
   service can start. This delivery releases no such slice.

The activation gate must reject every non-auth operational route, including
sync, RDO, worksite, financial, messaging, file, and administrative mutation
endpoints. It may expose only health/readiness and the bounded OTP path.

## Error Handling and Observability

- Missing, unreadable, non-owner-only, malformed, or reused bootstrap secrets
  fail before a source lookup or PostgreSQL write.
- Academy connection failures, ambiguous matches, inactive users, and missing
  or invalid source emails are generic operational failures; neither raw CPF
  nor source email appears in client-facing output or logs.
- PostgreSQL failures roll back the whole bootstrap transaction. The receipt
  is claimed only in the same transaction as identity creation.
- Activation-mode rejection is explicit and returns a stable machine-readable
  service-not-ready response rather than allowing a downstream dialect error.
- Sync remains unavailable until its PostgreSQL vertical slice preserves
  atomic `commit_seq`, outbox idempotency, device state, and operational
  Memory semantics.

## Verification Requirements

The implementation must prove all of the following:

1. A disposable PostgreSQL database can install V44 from an empty state and
   contains the expected final schema without any Córtex operational rows.
2. MySQL migration files are never selected by PostgreSQL migration mode.
3. A source MySQL connection is opened read-only and no bootstrap path issues
   a source mutation.
4. A valid one-time source record produces exactly one PostgreSQL ALFA with
   protected CPF lookup material, an administrative capability, a bootstrap
   receipt, and a redacted Memory event.
5. A second identical bootstrap does not duplicate the collaborator or
   capability; invalid, missing, inactive, ambiguous, or email-less source
   records leave PostgreSQL unchanged.
6. The direct-CPF endpoint cannot issue a session in PostgreSQL profiles.
7. OTP can activate the initial identity, while activation mode denies every
   non-allowlisted operational endpoint.
8. Normal PostgreSQL runtime refuses startup until V44, an activated ALFA,
   explicit runtime readiness, and a released PostgreSQL-safe vertical slice
   are present. `runtime-ready=true` alone is insufficient in this delivery.
9. The PostgreSQL final schema has no `arquivo_bytes` attachment/import column;
   `importacao_rdo` and `stavia_contexto_obra` retain their metadata/hash and
   have a nullable `storage_key` for the object-storage backend.

## Follow-on Delivery Sequence

Each subsequent slice gets its own approved specification and implementation
plan. The required order is:

1. PostgreSQL authentication/session runtime after clean-start activation.
2. Obras, collaborators, RDO creation, and map/GeoJSON operations.
3. Operational Memory, ontology, outbox, and offline synchronization with
   PostgreSQL atomic sequence semantics.
4. Messages, attachments, and object-storage metadata.
5. Financial, PDOR, reports, and remaining administrative domains.

No later slice may switch its traffic until its PostgreSQL tests show the
same transactional and authorization guarantees required by the Córtex
ontology and offline contract.

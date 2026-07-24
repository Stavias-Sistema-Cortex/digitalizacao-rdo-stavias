# Cortex 3 deep security scan

Current revision audit prepared: 2026-07-23. Scope: authentication, authorization,
offline/canonical sync, finance and PDOR, RDO attachments and export, web
delivery, PostgreSQL migrations, production configuration, and secret handling.

## Result

`PASS` for the verified V59 integration tree across source, authorization
tests, PostgreSQL integrations, generated frontend, dependency reports, export
fixtures, secret scanning, and Compose configuration. Authenticated local
runtime and real container-start evidence remain explicitly outside this
result because real identity/SMTP material and Docker-socket access were absent.

## Controls implemented and validated

- Production and `postgresql-common` disable direct CPF login. Production uses
  OTP/passkey authentication, requires mounted HMAC and signing-key files, and
  fails closed when key material or the configured auth mode is absent.
- Forwarded client addresses are trusted only from the fixed internal proxy
  subnet in the production compose example. The web image has an explicit auth
  mode and does not ship source maps.
- Finance and PDOR operations authorize the persisted worksite, not a worksite
  supplied only by the request. Mutation receipts bind entity, operation, and
  the complete normalized request SHA-256, including approval decisions and
  justification.
- Canonical sync accepts at most 64 dependencies, rejects duplicate and self
  references, and requires every dependency to be applied for the same owner,
  worksite, and schema v13. Exact replays revalidate the current owner and
  current worksite authorization before returning the stored receipt.
- RDO attachments bind the stored parent to the authorized worksite and enforce
  request, metadata, and object limits. OOXML inspection parses semantic XML and
  rejects macros, embeddings, external relationships, and active content.
- Logout persists a fail-closed local tombstone and retry state. Offline grants
  are signature verified and stored separately from private key material.
- Web delivery configures CSP and the reviewed security headers. The generated
  bundle passed the retired StavIA source/dist boundary. API and web containers
  run with `no-new-privileges`, all Linux capabilities dropped, read-only root
  filesystems, and narrowly scoped tmpfs/volume write surfaces. Both images use
  explicit non-root users.
- The required PostgreSQL chain ends at V59, including
  the retired runtime boundary, canonical v13 sync, operational Memory search,
  RDO creation context and receipt provenance, per-worksite graph scope,
  service pricing, immutable revenue evidence, PDOR revenue projection,
  canonical revenue-event integrity, and historical revenue ontology backfill.
- The active Financeiro surface is revenue-only: `Rastreio de receita`,
  `Serviços e preços`, and `PDOR`. Legacy cost, margin, purchase, rateio,
  invoice, payment, and collection surfaces are not part of the active product
  contract.

## Verification evidence

```text
./mvnw -f apps/api/pom.xml test
PASS — 970 tests; 0 failures; 0 errors; 54 skipped

./mvnw -f apps/api/pom.xml -Ppostgresql-it verify
PASS — 149 PostgreSQL 18.4 ITs; 0 failures/errors/skips; 17 migrations through V59

npm --prefix apps/web run lint
PASS — zero errors and warnings

npm --prefix apps/web run build
PASS — 224 modules; 99 PWA precache entries; StavIA source/dist boundary passed

npm --prefix apps/web test -- --run
PASS — 128 files; 652 tests

scripts/security/scan-cortex-secrets.sh
PASS — final staged runtime files and generated dist

scripts/security/test-local-compose-security.sh
PASS — PostgreSQL-only, loopback, secret mounts, non-root/read-only hardening

git diff --check
PASS

./mvnw org.owasp:dependency-check-maven:12.2.2:check \
  -Dformat=JSON -DfailBuildOnCVSS=7 -DautoUpdate=false \
  -DdataDirectory=/private/tmp/cortex-owasp-data
PASS — 100 dependencies; 0 vulnerabilities; 0 CVSS >= 7; 0 suppressed

npm --prefix apps/web audit --omit=dev
PASS — 0 vulnerabilities
```

Both local and production Compose models rendered successfully with safe
non-secret placeholders. A real container start was not claimed because the
sandbox denied the Docker socket; no permission escalation was requested.
Never inject a fake user or operational dataset merely to produce a signed-in
screenshot.

## Residual threat model

Operational offline data in IndexedDB is protected by application authorization,
signed offline grants, browser origin isolation, and the local OS/browser
profile. It is not additionally encrypted field by field at rest. A fully
compromised local operating-system account or browser profile remains outside
the web application's trust boundary. Production should therefore retain
managed-device controls, disk encryption, browser-profile protection, remote
session revocation, and short offline-grant lifetimes.

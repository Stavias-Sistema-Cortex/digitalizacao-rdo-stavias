# Cortex 3 deep security scan

Final validation: 2026-07-23. Scope: authentication, authorization,
offline/canonical sync, finance and PDOR, RDO attachments and export, web
delivery, PostgreSQL migrations, production configuration, and secret handling.

## Result

The final rescan found no confirmed open High, Medium, or Low finding in the
Cortex 3 delivery diff. The implementation closes the findings discovered
during the review instead of accepting them as deployment exceptions.

## Controls verified

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
- Web delivery applies CSP and the reviewed security headers. The generated
  bundle is scanned again and passes the retired StavIA runtime boundary.
- The PostgreSQL clean-start flow applies 12 migrations through V54, including
  the retired runtime boundary, canonical v13 sync, operational Memory search,
  RDO creation context, service pricing, immutable revenue evidence, and PDOR
  revenue projection evidence.

## Verification evidence

```text
mvn -o -f apps/api/pom.xml test
923 tests; 0 failures; 0 errors; 53 skipped Docker-gated integrations

PostgreSQL 18 Testcontainers suite
53 tests; 0 failures; 0 errors; 0 skipped; schema V54

npm --prefix apps/web run lint
PASS

npm --prefix apps/web run build
PASS; 224 modules; PWA 95 entries; retired StavIA boundary PASS

scripts/security/scan-cortex-secrets.sh
PASS; no unreviewed literal candidates

docker compose -f compose.production.example.yml config -q
PASS with every required production variable supplied

git diff --check
PASS
```

The final isolated frontend run executed all 516 assertions. It passed 514;
the remaining two geometry cases could not launch their separate headless
Chrome process because the command sandbox blocked the DevTools protocol. They
were not layout assertion failures. Both geometry cases had passed in the
earlier browser-enabled full run, and the final in-app browser check confirmed
that port 5173 is served from the Cortex 3 worktree and renders the current
institutional dark interface.

## Residual threat model

Operational offline data in IndexedDB is protected by application authorization,
signed offline grants, browser origin isolation, and the local OS/browser
profile. It is not additionally encrypted field by field at rest. A fully
compromised local operating-system account or browser profile remains outside
the web application's trust boundary. Production should therefore retain
managed-device controls, disk encryption, browser-profile protection, remote
session revocation, and short offline-grant lifetimes.

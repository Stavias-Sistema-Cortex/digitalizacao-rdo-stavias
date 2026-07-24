# Cortex 3 completion evidence

Current integrated worktree verified: 2026-07-23.

> The code, migration, export, dependency, and generated-bundle gates below
> were rerun on the integration tree immediately before its merge commit.
> Authenticated local-browser evidence is intentionally separate: the canonical
> database has no real ALFA identity or operational data, and the required
> bootstrap/SMTP secrets are absent.

## Delivered product contract

| Area | Implemented contract | Durable evidence or final gate |
| --- | --- | --- |
| StavIA and ontology | The StavIA launcher and assistant workflow are absent from the active frontend and generated frontend bundle. Compatibility controllers may remain registered in the backend; this document does not claim their removal. The ontology, knowledge graph, operational ledger, Memory search, recovery scheduler, and automatic schema-v13 offline sync remain independent capabilities. | Migration V45.1, graph/memory migrations V45-V47, frontend source/dist boundary test, PostgreSQL offline graph suite and full V59 gate. |
| RDO | Creation starts from an authorized existing worksite, fills the RDO identity, imports the previous RDO workforce, lets the foreman add/deselect workers and change the `apontador`, records executed services, and exports online or offline with fail-closed validation. | RDO context and provenance V48/V50/V55/V57, local repositories, creation tests, PostgreSQL RDO context suite, XLSX parity report and sample. |
| Financeiro and PDOR | The active Financeiro frontend contains only `Rastreio de receita`, `Serviços e preços`, and `PDOR`. Revenue comes from accepted RDO service execution and its immutable applicable price evidence. Cost, margin, purchases, rateios, invoices, payments, collections, and other legacy finance panels/routes are dormant and unreachable from the active frontend; this is not a claim that every legacy backend controller was removed. PDOR is revenue-only and records its ontology provenance transactionally when published. V59 specifically backfills the historical revenue-evidence ontology chain; it is not a PDOR-chain backfill. | Catalog migrations V49/V51, revenue evidence V52-V54, canonical revenue-event integrity V58, historical revenue-ontology backfill V59, 20 focused frontend contract tests, full web/API/PostgreSQL gates, and generated-chunk scan. |
| UI and offline operation | The institutional dark/minimal shell and product tabs use the primary workspace, avoid fabricated operational fallback, and retain PWA/offline automatic-sync paths. | Shared-shell, visual-policy, IndexedDB, lint/build, HTTP and DOM/CSS viewport gates. Authenticated pixel-level browser capture remains outside this evidence boundary. |

## RDO XLSX evidence boundary

The durable template comparison proves that the user attachment, the versioned
web template, and the server template have the same SHA-256:

```text
2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87
```

The online and offline workbooks were regenerated on 2026-07-23 from the
current integration tree. Their semantic contracts are equivalent across two
sheets, 68 populated value/type cells, all five operational segments, 149/52
merged ranges, and both print areas. The supplied template and every emitted
template copy retain the SHA-256 above. Artifact Tool rendered and visually
checked both sheets from the supplied, server-generated, and offline-generated
workbooks; all three scans found no formula or formula-error cells. The sample
is [RDO-offline-sample.xlsx](RDO-offline-sample.xlsx), and the reproducible
commands are in [rdo-export-evidence.md](rdo-export-evidence.md).

## Final verification matrix for the publish revision

```text
Backend full suite                 PASS — 970 tests; 0 failures/errors; 54 skipped
PostgreSQL 18 integration suite    PASS — 149 ITs; 0 failures/errors/skips
Flyway clean/upgrade chain          PASS — 17 migrations through V59 on PostgreSQL 18.4
Frontend full test run             PASS — 128 files; 652 tests
ESLint                              PASS — 0 errors/warnings
TypeScript/Vite production build   PASS — 224 modules
PWA generation                     PASS — 99 precache entries; service worker emitted
RDO online/offline XLSX parity     PASS — equivalent; 68 cells; 149/52 merges; 2 print areas
Retired StavIA source/dist check   PASS
Secret/key literal scanners        PASS — scanner rerun after final staging
Compose/security contract          PASS — PostgreSQL-only, secret mounts, rootless/read-only containers
OWASP dependency check             PASS — 100 dependencies; 0 vulnerabilities; 0 suppressed
npm production audit               PASS — 0 vulnerabilities
Git whitespace validation          PASS
UI geometry/HTTP                    PASS — DOM/CSS policies and /financeiro HTTP 200 on port 5174
Authenticated pixel/runtime flow   PENDING — no real ALFA, operational data, bootstrap or SMTP secrets
```

The Docker Compose model and hardening contract were rendered and validated.
Starting the containers themselves was not claimed because the sandbox denied
the Docker socket and no new permission was requested.

## Canonical local PostgreSQL state

The current local `StaviasCortex` database was migrated successfully through
V59 with zero failed Flyway migrations. Its exact 17-version PostgreSQL chain
is:

```text
44, 45, 45.1, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59
```

This local schema observation is independently backed by the clean-start and
upgrade-path PostgreSQL 18.4 integration gate. It does not create an
authenticated runtime identity or operational dataset.

Track each original requirement in
[completion-matrix.md](completion-matrix.md), and record live process,
datasource, readiness, authorization, viewport, and interaction facts in
[runtime-evidence.md](runtime-evidence.md).

## Runtime honesty

The canonical local database currently has `0` ALFA identities, `0` obras, and
`0` RDOs, and the environment has no real bootstrap or SMTP secrets. Therefore
authenticated local runtime behavior remains explicitly `PENDING`. A deployment
with real identity and SMTP material must record process/port,
health/readiness, authenticated role/scope, and the deployed revision. Never insert a fake
ALFA, worksite, workforce, RDO, service price, revenue value, or sync state to
manufacture a screenshot.

Security evidence is recorded in
[cortex-3-deep-scan.md](../../security/cortex-3-deep-scan.md) and
[secret-audit.md](secret-audit.md).

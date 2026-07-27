# Cortex 3.0 Design

**Status:** approved in conversation on 2026-07-21; written specification awaiting final user review.

## 1. Outcome

Cortex 3.0 turns the current application into an offline-first operational system whose source of truth is PostgreSQL `StaviasCortex`. The StavIA assistant is removed from every compiled frontend and backend runtime, while the operational ontology and knowledge graph become independent platform capabilities. RDO creation starts from a real worksite, carries the previous RDO workforce forward, exports the persisted RDO in the supplied two-sheet workbook shape, and supplies traceable production facts to Financeiro and PDOR. Financeiro owns versioned service prices; PDOR models revenue, not subjective cost. The authenticated workspaces use the available viewport without fabricated content.

This is one integrated program delivered in independently testable slices. A slice is complete only when its data model, authorization, offline behavior, automatic synchronization, PostgreSQL behavior, UI, and tests agree.

## 2. Terminology

- **STAVIAS** is the company/product brand and remains in logos and product copy.
- **StavIA** is the assistant/AI runtime and is removed from the executable product for Cortex 3.0.
- **Operational memory** is the authorized, chronological view of canonical operational events under `Home > Memória`.
- **Knowledge graph** is the materialized entity/relation/event/state/evidence projection derived from canonical business state and events.
- **Canonical mutation** is a versioned, idempotent user mutation persisted locally before synchronization and accepted or rejected atomically by the server.
- **RDO creation context** is a worksite-scoped snapshot containing worksite metadata, the next available RDO number, applicable schedules, service catalog entries, assets, collaborators, and the previous RDO workforce provenance.
- **Service price version** is the immutable price applicable to one contractual service during a defined validity interval.
- **Revenue evidence** is the chain from an accepted RDO service execution to its price version and calculated revenue.
- **PDOR** is the probabilistic/deterministic projection of final revenue derived from accepted evidence. It does not estimate subjective operational cost or margin.

## 3. Global invariants

1. No StavIA provider, launcher, page, route, controller, service, configuration block, or assistant-specific dependency is present in a compiled runtime.
2. Historical migrations are immutable. Legacy `stavia_*` database objects may remain for upgrade compatibility, but no Cortex 3 runtime reads or writes them.
3. Pure ontology models and projection logic live outside the archived StavIA tree and do not import assistant classes.
4. Every displayed entity and numeric result comes from persisted state, an authorized API response, or an explicitly labeled local draft/cache. Empty states never fabricate cards, people, work orders, services, prices, revenue, or synchronization success.
5. PostgreSQL is the only mutable server-side database for Cortex state. Academy and Zeladoria, when enabled, are read-only upstream sources.
6. User mutations are written locally first, remain usable offline, and synchronize automatically after authentication and connectivity return. Manual sync is diagnostic only, not required for correctness.
7. Every server mutation is authorized by worksite/entity scope, idempotent, version checked, and recorded as an operational event with actor, origin, client mutation ID, schema version, result, and affected entities.
8. Local databases and caches are partitioned by authenticated user identity and cleared or switched atomically on logout/user change.
9. Time instants crossing system boundaries use UTC `Instant`/ISO-8601 with offset. Local calendar dates remain `LocalDate` only where the domain is date-only.
10. Secrets never enter frontend code, browser storage, logs, source control, database migrations, generated exports, or error payloads.
11. Backward compatibility exists only where it preserves truthful behavior. Removed cost fields may be read during legacy import but are not exposed, recalculated, or written by Cortex 3.

## 4. Architecture

### 4.1 Runtime boundaries

The assistant implementation is moved, preserving history, to:

- `archive/stavia/backend/` for former backend source and tests;
- `archive/stavia/web/` for former frontend source and tests;
- `archive/stavia/README.md` documenting the source commit, reason for archival, restoration boundary, and prohibition on compiling the archive.

Before the move, reusable graph concepts are extracted into focused runtime units under `com.projeto.cortex.ontology.graph`:

- graph records for entity, relation, event, state, and evidence;
- authorized query controllers;
- PostgreSQL repositories;
- deterministic projectors;
- projection checkpoint and retry service;
- consistency/rebuild verifier.

Assistant-specific intents, response formatting, query logging, prompt construction, knowledge-source adapters, and reprogramming remain archived. The frontend removes `StaviaLauncherProvider`, launcher buttons, assistant affordances, and any explanatory copy implying an assistant is available.

An architecture contract fails the build if production sources contain imports from the archive, `/api/stavia` mappings, `StaviaLauncherProvider`, or assistant route registrations. The contract distinguishes the company spelling `Stavias` from the assistant spelling `StavIA` and does not reject legitimate branding.

### 4.2 Canonical write and projection flow

1. A UI repository opens one IndexedDB transaction.
2. It stores the domain snapshot/draft, a schema-versioned canonical mutation, and a correlated pending operational event atomically.
3. The automatic scheduler runs on login, online events, application focus, successful writes, and bounded exponential backoff.
4. The server authorizes scope, validates the entity references, checks idempotency and base version, applies the domain mutation, and appends the canonical event in one PostgreSQL transaction.
5. The graph projector consumes the committed event by monotonic commit sequence, upserts deterministic graph records, and advances its checkpoint in the same transaction.
6. The response atomically marks the local mutation/event `SYNCED`, `REJECTED`, or `CONFLICT` without deleting diagnostic evidence.
7. `Home > Memória` reads the same canonical event ledger and graph evidence, so its status is literal.

Different-field concurrent edits may merge only when both base and changed-field sets prove that the merge is lossless. Same-field conflicts remain visible for human review; they are never silently last-write-wins.

### 4.3 PostgreSQL strategy

The existing V44 PostgreSQL clean-start baseline remains unchanged. Cortex 3 adds ordered V45+ migrations for new indexes, price versions, execution price snapshots, graph projection checkpoints, RDO creation provenance, and any canonical mutation fields not already in the baseline.

Runtime repositories use PostgreSQL-native constructs:

- `jsonb` operators instead of `JSON_SEARCH`/`JSON_EXTRACT`;
- `INSERT ... ON CONFLICT` instead of `ON DUPLICATE KEY`;
- `timestamptz` and JDBC `Instant` for instants;
- partial unique indexes for current-state rows;
- explicit transactions and constraints rather than application-only uniqueness.

MySQL-only runtime paths are removed once the equivalent PostgreSQL slice is verified. The MySQL connector and Flyway MySQL module are removed only after a repository-wide runtime search and full test suite prove no production path needs them.

## 5. StavIA removal and independent ontology

### 5.1 Removal contract

The following disappear from the built frontend:

- launcher provider and floating launcher;
- launch actions in Home, Obras, RDO, Equipes, and Tarefas;
- assistant-specific dialogs, loading states, API clients, tests, and CSS;
- copy that promises AI responses.

The following disappear from the built backend:

- `/api/stavia/**` controllers;
- assistant snapshot, consultation, suggestion, reprogramming, and response-generation services;
- assistant query audit writes;
- `cortex.stavia` runtime configuration;
- compiled assistant tests and fixtures.

The graph remains available through `/api/ontology/**`, but the controllers and services are renamed and relocated so their API does not depend on StavIA types.

### 5.2 Graph projection

Projectors are deterministic and idempotent. A canonical event with the same commit ID always produces the same entity IDs, relation keys, state transitions, and evidence keys. Projection handles at minimum:

- obra/worksite;
- RDO and RDO workflow transitions;
- collaborator and workforce participation;
- equipment/asset usage;
- service catalog item and price version;
- executed service;
- revenue evidence;
- task/program schedule links;
- attachment/evidence references without embedding file content.

Projection failure does not roll back an already committed domain mutation. It records a retryable projection failure with sanitized diagnostics, leaves the checkpoint unchanged, and retries automatically. A rebuild command can recreate derived graph rows from canonical events in a temporary schema, compare counts/hashes, and swap only after consistency checks pass.

### 5.3 Graph API authorization

Every query derives allowed worksite IDs from the authenticated user. Entity detail is returned only if the entity or a traversed authoritative relation resolves to an allowed worksite. Traversals have explicit maximum depth, result count, and execution timeout. Search terms are bounded, normalized, parameterized, and never interpolated into SQL.

## 6. Home > Memória and search

Home has two internal views: `Visão geral` and `Memória`. Memória is the only user-facing ontology history ledger; other tabs may link to an event/entity but do not duplicate the ledger.

### 6.1 Query contract

`GET /api/ontology/memory` accepts:

- `q`: normalized text search over event type, entity names/IDs, actor, result summary, and selected non-sensitive payload text;
- `obraId`, `rdoId`, `entityType`, `entityId`, `eventType`, `origin`, `result`;
- `from`, `to` as UTC instants;
- `cursor` based on monotonic commit sequence and stable event ID;
- bounded `limit`.

The response contains items, next cursor, server high-water mark, authorization scope hash, and coverage metadata. PostgreSQL search uses indexed `tsvector`/trigram or an equivalent measured index; it must not scan arbitrary JSON for every request.

### 6.2 Offline search

Authorized memory pages and projection summaries are stored in a user-scoped IndexedDB store with a normalized search document. Offline search covers all locally cached authorized events, not merely the currently rendered page. The UI states one of:

- `Atualizado`: cache high-water mark equals the last confirmed server mark;
- `Parcial`: offline cache is valid but does not cover all authorized history;
- `Local pendente`: includes unsynchronized local events;
- `Sincronizando`, `Conflito`, or `Rejeitado` based on persisted state.

Search remains functional after reload without network. Reconnection automatically pulls from the last high-water mark, pushes pending mutations, reconciles statuses, and refreshes search documents.

## 7. RDO creation from a worksite

### 7.1 Entry flow

`Novo RDO` opens a worksite-first creation dialog populated only by authorized real worksites available from the server or the user-scoped offline cache. The user selects a worksite and date before entering the editor.

The client immediately generates a UUID for the draft. The creation context comes from the existing worksite-scoped context capability, extended to include:

- worksite snapshot and source version;
- collision-safe suggested next RDO number;
- applicable schedules and equipment;
- active service catalog/price versions for the date;
- previous eligible RDO ID, number, date, and version;
- previous RDO workforce rows with stable collaborator IDs;
- current authorized collaborators for additions;
- provenance and cache coverage.

When offline, the same context is derived from the last synchronized worksite, catalog, collaborator, schedule, and RDO snapshots. The UI labels stale or partial context and allows a local draft; it never invents a number, worker, service, or price. The server resolves any number collision during synchronization and returns the authoritative number while preserving the client UUID.

### 7.2 Workforce carry-forward

The most recent non-cancelled RDO for the selected worksite and before the selected date is the source. All workforce rows are imported as selected by default, keyed by collaborator ID. The editor shows their source RDO and allows the encarregado to:

- deselect a worker for the new RDO without altering the previous RDO;
- add another authorized collaborator;
- change hours, role, link type, and observations;
- select or clear the apontador from the selected workforce/current collaborator set.

Duplicate collaborator IDs are rejected. A worker absent from the current authorized catalog remains visible as historical provenance but is deselected and marked unavailable until replaced or explicitly authorized. No previous RDO produces a truthful empty workforce state with an invitation to add workers.

### 7.3 Service executions

Executed services are selected from active `item_contratual` price versions applicable to the RDO date. The RDO stores catalog ID, price-version ID, service name/unit snapshots, quantity, location, date, and validation state. The UI does not accept `custoRealizado` or collaborator `custoHora`.

Legacy imported values remain in historical storage only for audit compatibility. New and updated Cortex 3 payloads omit those cost fields, and response DTOs do not expose them.

### 7.4 Export

`GET /api/rdos/{id}/export.xlsx` returns an authorized XLSX generated with Apache POI from a versioned template resource based on `/Users/joaolucas/Downloads/RDO.xlsx` (SHA-256 `2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87`).

The workbook preserves the two operational sheets and visual hierarchy:

- front: header, obra/contract/RDO/date, weather, closure/turn, workforce, equipment/vehicles, worked segment, and activities;
- back: materials, production balances, observations, geometric control, general observations, and signatures.

The exporter maps only persisted RDO/worksite data. Unsupported cells remain blank. Variable-length sections add rows while retaining print areas, merged cells, styles, page setup, and the STAVIAS brand asset. Formula injection is prevented by writing external/user strings as literal text and neutralizing leading formula control characters. Filenames and `Content-Disposition` are sanitized.

Offline export uses the same mapping contract against the complete local RDO snapshot and a bundled template. The browser output is compared structurally with the server output; if local data is incomplete, export is disabled with an exact missing-data explanation rather than generating a misleading document.

## 8. Financeiro, service pricing, and PDOR

### 8.1 Service catalog and price versions

The existing `item_contratual` capability becomes the Financeiro service catalog. A service identity is stable across price changes. Editing price, unit, or validity creates a new immutable price version and closes/supersedes the previous version; it never rewrites historical evidence.

Financeiro provides authorized CRUD for:

- service code and description;
- unit of measure;
- worksite/contract;
- contracted quantity;
- unit price;
- validity start/end;
- active/inactive/superseded state;
- source and version provenance.

Overlapping active price intervals for the same worksite/contract/service are rejected. Deleting a referenced price version is prohibited; it may only be superseded or inactivated.

### 8.2 Revenue calculation

For each accepted RDO execution:

`revenue = accepted quantity × snapshotted unit price`

The calculation uses `BigDecimal`, the service unit scale, explicit rounding, and BRL currency scale. Rejected, cancelled, or retrabalho production contributes zero unless it is later represented by a separate accepted execution. Every result exposes:

- RDO and execution IDs;
- worksite and service IDs;
- price-version ID and validity;
- executed date and accepted quantity;
- unit and snapshotted unit price;
- calculated revenue;
- canonical event/evidence IDs;
- synchronization and validation state.

The Financeiro UI offers a revenue ledger and trace drawer derived from persisted evidence. Totals are sums of rows, never independent values returned without their components.

### 8.3 Revenue-only Financeiro surface

RDO, revenue forecasting, and PDOR remove:

- `custoRealizado` input and output;
- collaborator `custoHora` input and output;
- estimated cost, projected cost, operational margin, and margin percentage;
- data-quality warnings whose only purpose is missing subjective cost.

The active Financeiro product exposes only `Rastreio de receita`, `Serviços e
preços`, and `PDOR`. Purchases, invoices, payments, collections, allocations,
cost centers, cost, margin, and other legacy finance modules are not active or
reachable. Historical implementation code may remain dormant for compatibility,
but it is not an approved product surface and must not appear in navigation,
routes, Home summaries, exports, or runtime evidence.

### 8.4 PDOR

PDOR consumes only authorized, persisted evidence: contract/service price versions, accepted RDO executions, remaining contracted quantity, measured/approved/factured/received revenue states, schedule evidence when present, and explicit model parameters.

The snapshot records input evidence IDs, algorithm version, assumptions, data coverage, execution time, and output revenue distribution/point estimate. A recalculation failure is returned and logged safely; callers do not catch and suppress it while showing stale results as current. Historical snapshots remain immutable and clearly state their evidence high-water mark.

## 9. Offline and automatic synchronization

The canonical outbox foundation from the Cortex 2.1 work is ported by capability, not by merging its branch wholesale. This preserves current `develop` changes in App, login, Mensagens, and other workspaces.

Required IndexedDB stores include user-scoped versions of:

- worksites and creation contexts;
- RDO drafts/details/workforce/executions/attachments metadata;
- service catalog and price versions;
- canonical mutations and operational events;
- memory search documents and high-water marks;
- graph projection summaries needed by the UI;
- structured field conflicts.

Attachment binary synchronization uses existing storage controls and independent upload budgets; event payloads hold references, not binary content. Failed independent mutations continue; dependent mutations wait on their prerequisites. Retries use bounded exponential backoff with jitter and a terminal classification for validation/authentication failures.

Sync indicators report the persisted queue, not transient React state. Reloading the application cannot turn a pending or failed write into a false success.

## 10. UI system

The frontend-design direction is institutional and operational:

- preserve the existing sidebar and information architecture;
- use Poppins with restrained weights;
- use black/graphite as the structural accent rather than decorative gradients;
- prefer square or subtly rounded 2/4/6 px geometry;
- reserve color for semantic state and actions;
- maximize authenticated workspace width and height while retaining readable form measures;
- remove decorative empty columns, oversized hero whitespace, and fixed-height cards that clip real data;
- use container queries/responsive grids so 1080p and laptop widths remain usable;
- keep focus states, keyboard access, semantic landmarks, reduced motion, and WCAG AA contrast.

Every top-level tab uses a shared workspace shell with full available width, compact page header, optional sub-navigation, data/status rail, scrollable content region, and bounded dialogs. Dense tables use sticky headers and horizontal overflow only inside the table region. Forms use responsive columns and do not stretch short labels across the viewport.

No sample KPI, fake row, placeholder person, mock worksite, artificial revenue, or hardcoded synchronization status appears in production. Operational constants such as enum labels, empty-state copy, validation limits, and XLSX cell mappings are allowed because they define behavior rather than impersonate data.

## 11. Security and key handling

### 11.1 Authorization and data isolation

- Every endpoint requires authenticated scope; worksite access is checked before service/repository access.
- Entity IDs in requests, event `principalEntity`, and all `relatedEntities` are validated against type, existence, worksite scope, and relationship rules.
- Financeiro retains capability/grant checks in addition to worksite access.
- Export, attachments, memory search, graph traversal, PDOR history, and price versions receive dedicated object-level authorization tests.
- IndexedDB is partitioned by user and cannot expose the previous user's cached data after logout/login.

### 11.2 Web/API controls

- Same-site secure HTTP-only session cookies remain the browser credential; no bearer token is persisted in local storage.
- State-changing requests enforce the existing CSRF strategy and strict origin/CORS allowlist.
- CSP disallows unsafe remote script execution and frontend source maps are not publicly deployed with secrets.
- Request bodies, search strings, pagination, graph depth, workbook rows, attachments, and multipart envelopes have explicit size/count/time limits.
- Error responses use stable safe codes and correlation IDs; stack traces, SQL, file paths, secrets, CPF, email, and raw payloads are not returned.
- Authentication, export, search, upload, PDOR calculation, and graph traversal have endpoint-appropriate rate limits.

### 11.3 Secrets

Production secrets are injected by environment/file/secret manager under the existing fail-closed configuration. Startup rejects default, blank, malformed, reused, or wrong-environment keys. Logs expose only secret source/status/key ID where safe, never values. The frontend build is scanned for known secret names and high-entropy credentials. Git history/current tree, generated XLSX, browser storage, Docker/CI configuration, and runtime logs are included in the final secret audit.

## 12. Error handling and recovery

- Offline-capable writes succeed locally only after their atomic IndexedDB transaction commits.
- Authorization or schema rejection leaves the local mutation and event in `REJECTED` with safe reason and remediation.
- Version conflict leaves base/local/remote values in `CONFLICT` for review.
- Network errors remain `PENDING` and retry automatically.
- Projection failures are observable and retryable without lying about graph freshness.
- Price ambiguity prevents service selection/revenue calculation and directs Financeiro to repair validity intervals.
- Missing previous workforce produces an explicit empty state, not an error.
- Missing template or incomplete RDO prevents export and reports the exact administrative/data problem.
- PostgreSQL unavailability keeps the server fail-closed; the already-authenticated offline client continues within its authorized cached scope.

## 13. Delivery decomposition

Implementation plans will be separate but sequential and share the contracts in this specification:

1. **Runtime foundation:** PostgreSQL runtime activation, additive migrations, StavIA archival, ontology extraction, architecture guards.
2. **Canonical offline ontology:** mutation envelope/outbox, graph projector, Memória search/cache/conflicts.
3. **RDO workflow:** worksite-first creation, previous workforce, service selection, offline context, XLSX export.
4. **Revenue and PDOR:** price versions, execution snapshots, revenue evidence, removal of subjective cost, PDOR revision.
5. **Institutional UI:** shared workspace shell and focused tab migrations without fabricated data.
6. **Security and runtime proof:** abuse cases, deep frontend/backend scan, keys, PostgreSQL integration, browser offline/reconnect/export evidence.

Each plan starts with failing contract tests, ports only the required Cortex 2.1/Financeiro commits or code, and ends in an independently reviewable commit. The whole goal remains open until all six slices are integrated and the final audit passes.

## 14. Verification and acceptance

### 14.1 Automated evidence

- Architecture tests prove no executable StavIA code/routes/providers and no reverse dependency from ontology to archive.
- PostgreSQL Testcontainers migrations run from an empty database through V44 and all Cortex 3 migrations.
- Repository integration tests exercise actual PostgreSQL JSON, upsert, constraints, instants, authorization, and transactions.
- Backend controller/service tests cover cross-worksite denial, price overlap, workforce provenance, ID/number collision, export injection, traversal limits, related-entity validation, and secret-safe errors.
- Frontend tests cover worksite-first creation, carry-forward/deselect/add/apontador, offline reload, automatic retry, conflict persistence, truthful coverage labels, price selection, revenue trace, and absence of assistant UI/fake data.
- XLSX tests open the produced workbook with Apache POI and verify sheets, print areas, merged regions, styles, mapped values, blank unsupported cells, literal formula-like input, and variable-length sections.
- Static scans find no subjective cost fields in Cortex 3 RDO/PDOR APIs/UI and no MySQL-only SQL in active PostgreSQL runtime paths.

### 14.2 Runtime evidence

A fresh PostgreSQL `StaviasCortex` instance is migrated and the complete application starts in the PostgreSQL runtime profile. The validation scenario uses real persisted test fixtures, never production UI hardcoding:

1. authenticate as a scoped encarregado;
2. synchronize an authorized worksite, collaborators, service prices, and previous RDO;
3. go offline and reload;
4. create a new RDO from the cached worksite;
5. verify the client UUID and imported workforce, deselect one person, add another, and change/clear apontador;
6. record an executed priced service and export the two-sheet XLSX offline;
7. reconnect without pressing sync;
8. verify automatic idempotent persistence, authoritative RDO number, graph entities/relations/evidence, Memória search, and literal sync status;
9. verify revenue equals quantity × snapshotted price and PDOR uses the same evidence without cost/margin;
10. repeat reload/reconnect to prove no duplicate RDO, mutation, graph edge, or revenue;
11. attempt cross-worksite access/export/search and confirm denial without data leakage.

The same flow is repeated online against the API export. Local and server workbooks are compared structurally and visually rendered for inspection.

### 14.3 Visual evidence

Browser verification covers at least desktop 1440×900, laptop 1280×720, and narrow 390×844. It checks Home/Memória, RDO selection/editor/export, Financeiro catalog/revenue/PDOR, long real labels, empty states, pending/conflict states, keyboard navigation, and no clipped or wasted primary workspace.

### 14.4 Security completion gate

After functional implementation freezes, run the requested deep security workflow over the complete diff and reachable runtime. Validate every candidate finding, remediate confirmed in-scope issues, rerun targeted tests, and repeat discovery until a complete round yields no novel validated findings. The final report distinguishes verified results, residual environmental prerequisites, and anything not tested; it never claims production proof from static evidence alone.

## 15. Definition of done

Cortex 3.0 is done only when all of the following are simultaneously true:

- StavIA is absent from executable frontend/backend code and UI, with its source preserved in the non-built archive.
- Ontology and knowledge graph project automatically without StavIA and pass consistency/rebuild tests.
- Home > Memória performs authorized server and offline search with honest coverage and automatic synchronization.
- RDO creation requires a real worksite, generates/preserves its ID, carries forward the previous workforce, supports worker/apontador changes, works offline, and synchronizes automatically.
- RDO exports a truthful two-sheet XLSX matching the supplied operational model online and offline.
- Financeiro owns versioned service prices and revenue is traceable from accepted RDO execution to immutable price evidence.
- Financeiro exposes only revenue trace, service prices, and revenue-only PDOR;
  subjective cost, margin, and legacy accounting surfaces are not active or
  reachable.
- PDOR is reproducible from persisted evidence and reports failures/staleness honestly.
- Authenticated tab workspaces use the available area, remain responsive/accessible, and contain no fabricated production data.
- The full mutable Cortex runtime is proven on PostgreSQL `StaviasCortex` from clean migration through reconnect scenarios.
- Frontend, backend, object authorization, offline cache isolation, exports, uploads, secrets, and keys pass the final security gate.
- The reusable Cortex 3 delivery skill and its eval evidence are reviewed and committed.
- A requirement-by-requirement completion audit links every claim to current code, test output, runtime/browser evidence, or generated artifact.

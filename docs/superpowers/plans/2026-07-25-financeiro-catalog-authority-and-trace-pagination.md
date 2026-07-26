# Financeiro Catalog Authority and Trace Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the active Financeiro catalog the versioned contract authority for PDOR, reject unusable price inputs before offline queueing, and expose complete revenue evidence through bounded cursor pages.

**Architecture:** Add nullable historical-compatible `quantidade_contratada` storage to immutable `service_price_version`; require a positive value through every current write path and include it in ontology state. PDOR reads valid BRL catalog versions first and uses `item_contratual` only when no catalog version carries contract quantity. Revenue trace returns stable high-water cursor pages, while the web client validates and combines bounded pages before caching the complete server-confirmed snapshot.

**Tech Stack:** PostgreSQL/Flyway, Spring Boot/JdbcTemplate, Java records, React/TypeScript, IndexedDB/idb, Vitest, JUnit 5, Testcontainers.

## Global Constraints

- `docs/superpowers/specs/2026-07-21-cortex-3-0-design.md:203-220` is authoritative.
- Accepted revenue remains `accepted quantity × snapshotted unit price`, with exact decimal arithmetic and BRL scale.
- Catalog and price writes remain obra-scoped, immutable, idempotent, offline-first, and ontology-published.
- `item_contratual` is historical fallback only, never the sole authority for current catalog-created contracts.
- No auth or RDO sync/export files may change.
- Every production behavior follows a witnessed red test before its minimal implementation.

---

### Task 1: Versioned Contract Quantity

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V60__service_price_contract_quantity.sql`
- Modify: catalog command, response, repository, service, sync handler, and ontology publisher classes under `apps/api/src/main/java/com/projeto/cortex/financeiro/catalog/`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/catalog/ServicePriceCatalogServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/catalog/ServicePriceVersionSyncOperationHandlerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/catalog/PostgresqlServicePriceCatalogIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/catalog/PostgresqlServiceCatalogOntologyPublisherTest.java`

**Interfaces:**
- `CreateServicePriceCommand.contractedQuantity(): BigDecimal`
- `SupersedeServicePriceCommand.contractedQuantity(): BigDecimal`
- `ServicePriceVersion.contractedQuantity(): BigDecimal`
- `service_price_version.quantidade_contratada numeric(18,3)`

- [ ] Write service tests proving positive quantity is persisted and included in request identity, while zero/null is rejected.
- [ ] Run the focused service tests and capture the expected red assertions/compile failures.
- [ ] Add V60 and propagate `contractedQuantity` through commands, repository projections/inserts, sync payloads, responses, and ontology state.
- [ ] Run the focused service, sync, ontology, and PostgreSQL catalog tests green.

### Task 2: Catalog-Primary PDOR

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/RealPdorInputLoader.java`
- Modify/Test: `apps/api/src/test/java/com/projeto/cortex/pdor/PostgresqlPdorRevenueEvidenceIT.java`

**Interfaces:**
- Current authority query selects valid BRL `service_price_version` rows with positive `quantidade_contratada`.
- Contract value is `SUM(quantidade_contratada * valor_unitario)`.
- Planned quantity is `SUM(quantidade_contratada)`.
- Legacy fallback is used only when the catalog authority query returns no qualifying rows, and provenance names the fallback explicitly.

- [ ] Change the PostgreSQL fixture to configure only catalog service/price quantity plus accepted RDO evidence; remove its `item_contratual` insert.
- [ ] Run `PostgresqlPdorRevenueEvidenceIT` and capture failure for missing contract/planned quantity.
- [ ] Implement catalog-first contract and planned-quantity reads, dynamic provenance, and explicit legacy fallback.
- [ ] Run PDOR PostgreSQL evidence tests green, including a focused historical-fallback case.

### Task 3: Offline Contract Quantity and Input Validation

**Files:**
- Modify: `apps/web/src/features/financeiro/servicePriceApi.ts`
- Modify: `apps/web/src/features/financeiro/servicePriceRepository.ts`
- Modify: `apps/web/src/features/financeiro/ServicePriceCatalogPage.tsx`
- Modify: `apps/web/src/lib/db/db.types.ts`
- Test: `apps/web/src/features/financeiro/servicePriceApi.test.ts`
- Test: `apps/web/src/features/financeiro/servicePriceRepository.test.ts`
- Test: `apps/web/src/features/financeiro/ServicePriceCatalogPage.test.tsx`

**Interfaces:**
- `CreateLocalPriceInput.contractedQuantity: string`
- `SupersedeLocalPriceInput.contractedQuantity: string`
- Local/remote price versions expose exact scale-three contract quantity.
- Currency input is fixed to `BRL`.
- Source accepts `[A-Z0-9][A-Z0-9._:-]{0,79}` and the UI example uses `CONTRATO_MEDIDO`.

- [ ] Add repository tests proving non-BRL, invalid source, and non-positive quantity are rejected with no outbox/local price record.
- [ ] Add UI tests proving quantity is queued and the displayed source example is backend-valid.
- [ ] Run those tests and capture red.
- [ ] Add exact local normalization, IndexedDB field hydration, BRL-only UI, quantity controls, valid source helper, and catalog display.
- [ ] Run focused web tests green.

### Task 4: Bounded Complete Revenue Trace

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaResponse.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaService.java`
- Modify/Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaServiceLimitTest.java`
- Modify/Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaResponseContractTest.java`
- Modify/Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/PostgresqlRevenueTraceIT.java`
- Modify: `apps/web/src/features/financeiro/servicePriceApi.ts`
- Modify: `apps/web/src/features/financeiro/revenueTraceCacheRepository.ts`
- Modify/Test: `apps/web/src/features/financeiro/servicePriceApi.test.ts`
- Modify/Test: `apps/web/src/features/financeiro/revenueTraceCacheRepository.test.ts`

**Interfaces:**
- GET accepts `cursor` and `limit` (default bounded, maximum 500).
- Each response page exposes `nextCursor`, `coverage`, and `highWaterMark`; `totalRevenue` remains the exact sum of that page’s rows.
- Cursor binds authorized scope and date filters, carries the evidence high-water mark and last `(eventCommitSequence, executionId)`, and rejects malformed/reused-filter tokens.
- `fetchCompleteRevenueTrace()` follows at most 1,000 non-repeating cursors, enforces a stable high-water mark, rejects duplicate evidence IDs, and returns a complete response whose total is the exact sum of all rows.

- [ ] Replace the old 501-row rejection test with page-boundary, cursor-binding, and no-duplicate tests.
- [ ] Run the API tests and capture red.
- [ ] Implement bounded SQL keyset pages and cursor metadata.
- [ ] Run focused API and PostgreSQL trace tests green.
- [ ] Add web tests for multi-page completion, stable snapshot, duplicate/repeated cursor rejection, and exact aggregate total.
- [ ] Run web tests and capture red.
- [ ] Implement the complete-page fetcher and use it for the server-confirmed cache.
- [ ] Run focused web tests green.

### Task 5: Regression and Scope Gate

**Files:**
- Review all files changed by Tasks 1-4.

- [ ] Run Financeiro/RDO/PDOR focused web tests.
- [ ] Run focused API contract/unit tests.
- [ ] Run PostgreSQL catalog, revenue-evidence, revenue-trace, and PDOR evidence tests through V60.
- [ ] Run web production build and API package/compile gate.
- [ ] Verify `git diff --name-only` contains no auth or RDO sync/export files added by this implementation and preserve all pre-existing unrelated changes.
- [ ] Record any environment-blocked gate explicitly rather than claiming it passed.

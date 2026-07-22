# Financeiro / PDOR Completion Audit

**Verdict:** do not mark the Revenue/PDOR slice complete.  No claim is
`PROVEN`. Green unit/controller tests are insufficient where they do not prove
immutable evidence, PostgreSQL constraints, offline persistence, automatic
reconnect, or the visible evidence ledger.

## Requirement matrix

| Claim | Classification | Current evidence | Evidence needed for `PROVEN` |
|---|---|---|---|
| 1. Service price can be edited in Financeiro. | **CONTRADICTED** | The supplied fixture says price edits update the existing `item_contratual` row in place. This violates the required immutable service-price-version contract. Static inspection also finds no V49 migration, `service_price_version` table, catalog service, or catalog UI; `ItemContratualService` writes `preco_unitario` directly to `item_contratual`. | An authorized Financeiro price catalog that creates/supersedes immutable versions; PostgreSQL V49 with the active-validity non-overlap constraint; a PostgreSQL integration test proving old price evidence remains unchanged, overlap rejection, and referenced-version deletion rejection; UI/offline tests for the same contract. |
| 2. Revenue is calculated from the RDO. | **CONTRADICTED** | `RevenueCalculatorTest` proves only `10 × 125 = 1250`. The fixture states executions do not retain a price-version ID or a price snapshot. Current RDO code calculates from `itemContratual.precoUnitario()` and persists `receita_operacional_estimativa`; the PostgreSQL baseline lacks `price_version_id`, `unit_price_snapshot`, `revenue_amount`, and `revenue_event_id`. A later price edit can therefore rewrite the economic meaning of historical execution. | V50 execution evidence columns/constraint and an atomic accepted-execution transaction that validates date/unit/worksite, snapshots immutable price ID and unit price, rounds BRL with `HALF_UP`, and records the canonical revenue event. PostgreSQL tests must cover supersession, accepted versus rejected/cancelled/rework rows, replay idempotency, and a trace response whose total is the sum of visible rows. |
| 3. PDOR no longer depends on subjective cost. | **CONTRADICTED** | The fixture's `PdorResult` contains `projectedCost` and `margin`. Current source also still records `custo_realizado` in RDO execution and calculates/exposes `custoRealizado`, `custoPrevistoFinal`, and margin values in `PrevisaoFinanceiraService`. This is the prohibited semantics, not merely missing test coverage. | A revenue-only immutable PDOR snapshot/response/input contract that excludes cost and margin from new Cortex 3 payloads and writes; tests/static scans covering PDOR, RDO, and Financeiro production-revenue paths; PostgreSQL evidence test proving evidence IDs, high-water mark, assumptions, algorithm version, coverage, UTC execution time, stale/failure handling, and reproducibility. |
| 4. Ontology is central and functional. | **CONTRADICTED** | The fixture says no graph relation or evidence IDs appear in the response. The required revenue graph chain (`RDO_EXECUTION -> PRICED_BY -> SERVICE_PRICE_VERSION`, `EXECUTES_SERVICE`, `BELONGS_TO_WORKSITE`, `PRODUCES_REVENUE`) is therefore neither exposed nor evidenced. Existing generic memory/ontology behavior cannot establish this revenue-specific claim. | A canonical-event projector that emits the required deterministic revenue relations/evidence; scoped graph/ledger API responses containing RDO, execution, service, price-version, event, and graph IDs; tests for authorization and replay idempotency; PostgreSQL integration evidence that no duplicate edges are created. |
| 5. The slice works offline on PostgreSQL. | **MISSING** | The fixture explicitly supplies no PostgreSQL integration, IndexedDB, reconnect, or browser test. Existing generic sync support does not prove offline service-price mutations, cached price coverage, revenue trace, or automatic reconnect for this slice. | PostgreSQL Testcontainers verification for V49/V50 and repositories/transactions; user-scoped IndexedDB catalog/evidence/outbox stores with offline reload tests; automatic reconnect/push tests without a manual sync action; browser/runtime proof using persisted fixtures and literal sync/coverage states. |

## Scope check

The supplied `RevenueCalculatorTest` and `PdorControllerTest` are **INDIRECT**
evidence only: arithmetic and HTTP 200 do not validate the slice contract.
The Financeiro UI total card is also **INDIRECT** because it has no component
evidence rows or trace drawer. Neither changes the classifications above.

## Audit basis

- Required contract: `docs/superpowers/specs/2026-07-21-cortex-3-0-design.md`, section 8; `docs/superpowers/plans/2026-07-21-cortex-3-0-revenue-pdor.md`, Tasks 1–5.
- Fixture: `skills/cortex-3-delivery/evals/files/revenue-pdor-fixture.md`.
- Current static corroboration: no V49/V50 PostgreSQL migration or price/evidence columns; RDO execution uses current item price and persists subjective cost; Financeiro/PDOR source still contains cost and margin semantics.
- No runtime, browser, reconnect, PostgreSQL-integration, or IndexedDB test was run as part of this read-only audit.

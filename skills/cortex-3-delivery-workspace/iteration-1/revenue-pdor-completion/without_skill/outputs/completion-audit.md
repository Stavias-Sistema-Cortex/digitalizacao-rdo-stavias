# Financeiro / PDOR completion audit — baseline (fixture-only)

Scope: this audit evaluates only the supplied `revenue-pdor-fixture.md`. It does not infer passing tests, runtime behavior, or architecture beyond that fixture.

| Claim | Classification | Fixture evidence | Evidence still needed before marking complete |
| --- | --- | --- | --- |
| Service price can be edited in Financeiro. | INDIRECT | The fixture says price edits update the existing `item_contratual` row in place. This establishes a persistence-side edit effect, but not a Financeiro user flow or its successful display/error behavior. | A Financeiro UI or end-to-end test that edits a service price, verifies persistence and reload, and identifies authorization/validation behavior. |
| Revenue is calculated from the RDO. | INDIRECT | `revenue(Execution)` multiplies an execution quantity by the current item price; `RevenueCalculatorTest` verifies `10 × 125 = 1250`. The fixture does not establish that the `Execution` quantity is an RDO-derived/approved quantity, nor that the total is a complete RDO aggregation. It also reads the mutable current price rather than a price version or snapshot. | A test tracing an approved RDO to its execution/revenue result, including multi-line/aggregation semantics; a defined historical-price rule and a regression test proving price edits do not silently rewrite historical revenue. |
| PDOR no longer depends on subjective cost. | MISSING | `PdorResult` contains `projectedCost`, but the fixture supplies neither its source nor a rule that eliminates subjective/manual cost input. | The PDOR cost model, source-of-truth entities and calculation tests; evidence that manual/subjective inputs are excluded or explicitly governed, with provenance in the result. |
| Ontology is central and functional. | INCOMPLETE | No graph relation or evidence IDs appear in the response, and the Financeiro UI exposes only a total card rather than component evidence rows. The fixture offers no successful ontology traversal, write, or query. | An integration/e2e test showing Financeiro/PDOR writes and reads ontology relations/evidence, returned trace IDs, and UI access to component-level provenance. |
| The slice works offline on PostgreSQL. | INCOMPLETE | The fixture expressly reports no PostgreSQL integration, IndexedDB, reconnect, or browser test. | A PostgreSQL integration test plus browser/e2e offline test: load persisted data, simulate offline edits/reads, reconnect, and verify conflict/sync semantics and durable state. |

## Completion decision

Do not mark the Financeiro/PDOR slice complete from this fixture. No claim is proven at the requested product level; the supplied unit/controller checks cover only a narrow formula and HTTP success path.

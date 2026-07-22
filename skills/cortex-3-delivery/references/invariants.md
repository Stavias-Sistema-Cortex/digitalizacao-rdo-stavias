# Cortex 3 Cross-Slice Invariants

## Identity and authorization

- Stable business identities are IDs, not names or labels.
- Every object resolves to an authorized worksite before its data is returned or mutated.
- `principalEntity` and every `relatedEntity` are type/existence/scope validated.
- Offline stores include the authenticated subject in their namespace/key strategy.

## Mutation and projection

- Local domain write, outbox record, and event evidence commit atomically.
- Server domain mutation and canonical event commit atomically.
- Projection is deterministic, idempotent, checkpointed, and retryable.
- Replay preserves one domain mutation, one canonical event, one graph fact set, and one revenue result.

## Revenue

- A service identity is stable; price versions are immutable.
- Execution snapshots the applicable price version at the RDO date.
- Historical revenue never changes when a later price is created.
- Cost and margin are absent from RDO/PDOR; recorded accounting transactions remain separate.

## Honest UI

- Hardcoded enum labels, validation limits, empty-state copy, and XLSX cell mappings are behavior, not fake data.
- Hardcoded people, worksites, RDOs, services, prices, KPIs, revenue, or sync success are fake data and prohibited.
- Offline/partial/pending/conflict/rejected states derive from persisted evidence.

## Proof strength

- Static search proves absence only within its exact searched scope.
- Unit tests prove their unit, not runtime wiring.
- Integration tests prove the exercised database/profile, not production infrastructure.
- Browser verification proves the recorded flow/viewport/commit, not untested roles or environments.


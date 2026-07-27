# Cortex 3 offline memory evidence

Focused flows were first verified on 2026-07-22. On 2026-07-23 the current
integration tree passed the full 970-test API suite, 149 PostgreSQL 18.4
integration tests through V59, and the 652-test frontend suite. Authenticated
browser behavior remains a separate deployment proof.

The canonical local database has since been migrated through the exact
17-version chain `44,45,45.1,46,47,48,49,50,51,52,53,54,55,56,57,58,59`
with zero failed migrations. The clean-start/upgrade integration gate applied
the same chain. The local database has `0` ALFA identities, `0` obras, and `0`
RDOs, so authenticated offline/browser flows remain `PENDING`.

## Covered flows

- A schema-v13 offline RDO is applied through `SyncService`,
  `RdoSyncOperationHandler`, and `RdoService`.
- RDO creation-context provenance spans V48/V50/V55/V57: scoped context,
  canonical receipt, actor/capacity indexes, and the validated RDO-to-receipt
  foreign key are all part of the invariant.
- Replaying the same canonical mutation from a second authorized device returns
  the original commit without a second RDO, sync receipt, or ledger event.
- A PostgreSQL trigger forces the durable graph projection to fail after the
  domain transaction commits. The checkpoint exposes only
  `GRAPH_PROJECTION_FAILED`; the domain and canonical ledger remain committed.
- The production `GraphProjectionRecoveryScheduler` is run twice after the
  fault is disabled. It creates one graph event, advances one checkpoint, clears
  the safe error, and makes Memory graph coverage fresh without duplicates.
- A delayed status event is inserted between its predecessor and successor.
  PostgreSQL assertions prove no negative interval, no overlap, one open state,
  and idempotent replay.
- Free-form `description` is absent from RDO, obra, and service event payloads,
  entity descriptions, persisted graph rows, and scoped query projections.
- The browser sync engine rehydrates multiple Memory pages after push, reopens
  IndexedDB offline, and finds an event stored on the second page.

V59 has a separate financial purpose: it backfills the historical
revenue-evidence ontology chain. It does not backfill a PDOR ontology chain;
PDOR provenance is recorded transactionally when a PDOR is published.

## Exact verification commands

Run from `apps/api`:

```bash
./mvnw -Dtest=OperationalGraphPayloadPolicyTest,OperationalGraphProjectorTest,PostgresqlOntologyGraphRepositoryIT test
```

Result: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` and
`BUILD SUCCESS`.

```bash
./mvnw -Dtest=PostgresqlOfflineGraphFlowIT test
```

Result: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` and
`BUILD SUCCESS`. During the forced-failure case the listener reported
`GRAPH_PROJECTION_FAILED`; the production scheduler then reported one recovered
event at checkpoint 1.

Run from `apps/web`:

```bash
npm test -- src/lib/sync/syncMemoryOfflineFlow.test.ts
```

Result: `Test Files 1 passed (1)` and `Tests 3 passed (3)`.

## Evidence locations

- `apps/api/src/test/java/com/projeto/cortex/ontology/PostgresqlOfflineGraphFlowIT.java`
- `apps/api/src/test/java/com/projeto/cortex/ontology/graph/PostgresqlOntologyGraphRepositoryIT.java`
- `apps/api/src/test/java/com/projeto/cortex/ontology/graph/OperationalGraphPayloadPolicyTest.java`
- `apps/web/src/lib/sync/syncMemoryOfflineFlow.test.ts`

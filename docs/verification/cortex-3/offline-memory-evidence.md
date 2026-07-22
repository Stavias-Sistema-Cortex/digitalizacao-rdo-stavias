# Cortex 3 offline memory evidence

Verified on 2026-07-22 against PostgreSQL 18 with the production sync, ledger,
graph projection, recovery, and Memory query components.

## Covered flows

- A schema-v13 offline RDO is applied through `SyncService`,
  `RdoSyncOperationHandler`, and `RdoService`.
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
